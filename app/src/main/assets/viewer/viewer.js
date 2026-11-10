// Просмотрщик EasyPDF: pdf.js + готовые компоненты PDFViewer / поиск.
// С Android общается через window.Android (JS → приложение) и window.EasyPDF (приложение → JS).
import * as pdfjsLib from './lib/pdf.min.mjs';

globalThis.pdfjsLib = pdfjsLib;
pdfjsLib.GlobalWorkerOptions.workerSrc = './lib/pdf.worker.min.mjs';
const { EventBus, PDFViewer, PDFLinkService, PDFFindController } = await import('./lib/pdf_viewer.mjs');

// Вызов метода приложения; в обычном браузере (при отладке) просто ничего не делает.
function call(name, ...args) {
  const a = window.Android;
  if (a && typeof a[name] === 'function') a[name](...args);
}

const params = new URLSearchParams(location.search);
const startPage = Math.max(1, parseInt(params.get('page') || '1', 10) || 1);
const docUrl = params.get('doc') || '/doc/current.pdf';
// Высота верхней панели приложения: она лежит поверх документа и прячется при прокрутке.
const topInset = Math.max(0, parseInt(params.get('top') || '0', 10) || 0);
document.getElementById('viewer').style.paddingTop = topInset + 'px';
if (params.get('night') === '1') document.body.classList.add('night');
// Сохранённые для этого файла поворот и масштаб (масштаб — относительно «по ширине экрана»).
const startRotation = ((parseInt(params.get('rot') || '0', 10) || 0) % 360 + 360) % 360;
const startZoom = parseFloat(params.get('zoom') || '1') || 1;
// Большие файлы читаем частями, а не целиком в память.
const useRange = params.get('range') === '1';

const container = document.getElementById('viewerContainer');
const statusBox = document.getElementById('status');
const statusText = document.getElementById('statusText');

const eventBus = new EventBus();
const linkService = new PDFLinkService({ eventBus, externalLinkTarget: 0 });
const findController = new PDFFindController({ eventBus, linkService, updateMatchesCountOnProgress: true });
const pdfViewer = new PDFViewer({
  container,
  viewer: document.getElementById('viewer'),
  eventBus,
  linkService,
  findController,
  textLayerMode: 1,
  annotationMode: pdfjsLib.AnnotationMode.ENABLE,
  imageResourcesPath: 'lib/images/',
  removePageBorders: true,
});
linkService.setViewer(pdfViewer);

let fitWidth = true;      // масштаб «по ширине экрана», пока пользователь не изменил его сам
let fitScale = 1;         // чему равен масштаб «по ширине»
let passwordCallback = null;
let numPages = 0;

function applyFit() {
  pdfViewer.currentScaleValue = 'page-width';
  fitScale = pdfViewer.currentScale;
}

// --- история переходов: «Назад» возвращает туда, откуда прыгнули ---
const history = [];
function pushHistory() {
  if (!numPages) return;
  const entry = { top: container.scrollTop, left: container.scrollLeft, scale: pdfViewer.currentScale };
  const last = history[history.length - 1];
  if (last && Math.abs(last.top - entry.top) < 4 && last.scale === entry.scale) return;
  history.push(entry);
  if (history.length > 50) history.shift();
  call('onHistory', history.length);
}
function clearHistory() {
  if (!history.length) return;
  history.length = 0;
  call('onHistory', 0);
}
// Переходы по ссылкам внутри документа и по оглавлению идут через linkService.
for (const method of ['goToDestination', 'goToPage']) {
  const original = linkService[method].bind(linkService);
  linkService[method] = (...args) => {
    pushHistory();
    programmatic = true;
    return original(...args);
  };
}

// --- поворот и масштаб запоминаются для файла ---
let viewTimer = 0;
function reportView() {
  clearTimeout(viewTimer);
  viewTimer = setTimeout(() => {
    if (!numPages || !fitScale) return;
    const zoom = fitWidth ? 1 : pdfViewer.currentScale / fitScale;
    call('onView', pdfViewer.pagesRotation | 0, Math.round(zoom * 1000) / 1000);
  }, 300);
}
eventBus.on('scalechanging', reportView);
eventBus.on('rotationchanging', reportView);

// --- какие страницы сейчас на экране ---
let lastFirst = 0, lastLast = 0;
function pageVisible(n, top, bottom) {
  const view = pdfViewer.getPageView(n - 1);
  if (!view || !view.div) return false;
  const r = view.div.getBoundingClientRect();
  const overlap = Math.min(r.bottom, bottom) - Math.max(r.top, top);
  return overlap >= 0.15 * Math.min(r.height, bottom - top);
}
function reportPages() {
  if (!numPages) return;
  const c = container.getBoundingClientRect();
  const current = pdfViewer.currentPageNumber;
  let first = current, last = current;
  while (first > 1 && pageVisible(first - 1, c.top, c.bottom)) first--;
  while (last < numPages && pageVisible(last + 1, c.top, c.bottom)) last++;
  if (!pageVisible(first, c.top, c.bottom) && first < last) first++;
  if (first !== lastFirst || last !== lastLast) {
    lastFirst = first; lastLast = last;
    call('onPages', first, last);
  }
}
let reportQueued = false;
function queueReport() {
  if (reportQueued) return;
  reportQueued = true;
  requestAnimationFrame(() => { reportQueued = false; reportPages(); });
}
container.addEventListener('scroll', queueReport, { passive: true });
eventBus.on('scalechanging', queueReport);
eventBus.on('pagechanging', queueReport);
eventBus.on('pagesloaded', queueReport);

// --- верхняя панель: прячется при прокрутке вниз, возвращается при прокрутке вверх и по касанию ---
let barShown = true;
let searchMode = false;
let programmatic = true;   // прокрутка вызвана не пальцем (переход к странице, поиск, оглавление)
let lastTop = 0;
function setBar(show) {
  if (searchMode || show === barShown) return;
  barShown = show;
  call('onBar', show);
}
container.addEventListener('touchstart', () => { programmatic = false; }, { passive: true });
container.addEventListener('scroll', () => {
  let top = container.scrollTop;
  // pdf.js ставит страницу вплотную к верху, то есть под панель. В самом начале
  // документа возвращаемся к нулю, чтобы первая страница была видна целиком.
  if (programmatic && !searchMode && top > 0 && top <= topInset) {
    container.scrollTop = 0;
    top = 0;
  }
  const delta = top - lastTop;
  lastTop = top;
  if (top <= topInset) setBar(true);
  else if (programmatic) setBar(false);
  else if (delta > 4) setBar(false);
  else if (delta < -4) setBar(true);
  showScrubber();
}, { passive: true });

// --- бегунок быстрой прокрутки у правого края ---
const scrubber = document.getElementById('scrubber');
const scrubLabel = document.getElementById('scrubLabel');
let scrubTimer = 0;
let scrubDrag = null;
function scrubTrack() {
  const from = topInset + 8;
  const to = Math.max(from + 1, window.innerHeight - 8 - scrubber.offsetHeight);
  return [from, to];
}
function scrollRange() {
  return container.scrollHeight - container.clientHeight;
}
function showScrubber() {
  const max = scrollRange();
  // В коротком документе бегунок не нужен.
  if (numPages < 3 || max < container.clientHeight * 2) {
    scrubber.classList.remove('visible');
    return;
  }
  const [from, to] = scrubTrack();
  scrubber.style.top = (from + (to - from) * Math.min(1, container.scrollTop / max)) + 'px';
  scrubber.classList.add('visible');
  if (scrubDrag) scrubLabel.textContent = pdfViewer.currentPageNumber + ' / ' + numPages;
  clearTimeout(scrubTimer);
  if (!scrubDrag) scrubTimer = setTimeout(() => scrubber.classList.remove('visible'), 1500);
}
scrubber.addEventListener('touchstart', (e) => {
  if (e.touches.length !== 1) return;
  e.preventDefault();
  pushHistory();
  programmatic = true;
  scrubDrag = { offset: e.touches[0].clientY - scrubber.getBoundingClientRect().top };
  scrubber.classList.add('dragging');
  showScrubber();
}, { passive: false });
scrubber.addEventListener('touchmove', (e) => {
  if (!scrubDrag) return;
  e.preventDefault();
  const [from, to] = scrubTrack();
  const y = e.touches[0].clientY - scrubDrag.offset;
  const ratio = Math.min(1, Math.max(0, (y - from) / (to - from)));
  programmatic = true;
  container.scrollTop = ratio * scrollRange();
}, { passive: false });
const endScrub = () => {
  if (!scrubDrag) return;
  scrubDrag = null;
  scrubber.classList.remove('dragging');
  showScrubber();
};
scrubber.addEventListener('touchend', endScrub);
scrubber.addEventListener('touchcancel', endScrub);

// --- масштаб двумя пальцами и двойным касанием ---
let pinch = null;
function touchDistance(e) {
  const [a, b] = [e.touches[0], e.touches[1]];
  return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
}
function zoomTo(target, origin, delay) {
  const clamped = Math.min(Math.max(target, fitScale * 0.5), Math.max(fitScale * 8, 4));
  const factor = clamped / pdfViewer.currentScale;
  if (Math.abs(factor - 1) < 0.01) return;
  fitWidth = false;
  pdfViewer.updateScale({ drawingDelay: delay, scaleFactor: factor, origin });
}
container.addEventListener('touchstart', (e) => {
  if (e.touches.length === 2) {
    pinch = { distance: touchDistance(e), scale: pdfViewer.currentScale };
  }
}, { passive: true });
container.addEventListener('touchmove', (e) => {
  if (!pinch || e.touches.length !== 2) return;
  e.preventDefault();
  const [a, b] = [e.touches[0], e.touches[1]];
  const origin = [(a.clientX + b.clientX) / 2, (a.clientY + b.clientY) / 2];
  zoomTo(pinch.scale * touchDistance(e) / pinch.distance, origin, 400);
}, { passive: false });
const endPinch = (e) => { if (e.touches.length < 2) pinch = null; };
container.addEventListener('touchend', endPinch, { passive: true });
container.addEventListener('touchcancel', endPinch, { passive: true });

let lastTap = null;
let tapTimer = 0;
let touchStart = null;
container.addEventListener('touchstart', (e) => {
  touchStart = e.touches.length === 1
    ? { x: e.touches[0].clientX, y: e.touches[0].clientY, time: performance.now() }
    : null;
}, { passive: true });
container.addEventListener('touchend', (e) => {
  clearTimeout(tapTimer);
  if (e.touches.length !== 0 || e.changedTouches.length !== 1 || !touchStart) { lastTap = null; return; }
  const t = e.changedTouches[0];
  const now = performance.now();
  // Касанием считаем только короткое нажатие без сдвига (не прокрутку и не выделение текста).
  if (now - touchStart.time > 350 || Math.hypot(t.clientX - touchStart.x, t.clientY - touchStart.y) > 12) {
    lastTap = null;
    return;
  }
  if (lastTap && now - lastTap.time < 300 &&
      Math.hypot(t.clientX - lastTap.x, t.clientY - lastTap.y) < 30) {
    lastTap = null;
    if (window.getSelection && String(window.getSelection())) return;
    if (pdfViewer.currentScale > fitScale * 1.05) {
      fitWidth = true;
      applyFit();
    } else {
      zoomTo(fitScale * 2.5, [t.clientX, t.clientY], 0);
    }
  } else {
    lastTap = { time: now, x: t.clientX, y: t.clientY };
    const onLink = e.target && e.target.closest && e.target.closest('a, .linkAnnotation');
    if (!onLink) {
      // Ждём, не окажется ли касание двойным, и только потом переключаем панель.
      tapTimer = setTimeout(() => {
        if (window.getSelection && String(window.getSelection())) return;
        setBar(!barShown);
      }, 310);
    }
  }
}, { passive: true });

window.addEventListener('resize', () => {
  if (!numPages) return;
  clearHistory();
  const page = pdfViewer.currentPageNumber;
  if (fitWidth) {
    applyFit();
    pdfViewer.currentPageNumber = page;
  } else {
    const old = fitScale;
    const scale = pdfViewer.currentScale;
    applyFit();
    fitScale = pdfViewer.currentScale;
    pdfViewer.currentScale = scale * (fitScale / old);
  }
  queueReport();
});

// --- поиск ---
function reportFind(state, count) {
  call('onFind', count ? count.current || 0 : 0, count ? count.total || 0 : 0, state);
}
eventBus.on('updatefindmatchescount', ({ matchesCount }) => reportFind(-1, matchesCount));
eventBus.on('updatefindcontrolstate', ({ state, matchesCount }) => reportFind(state, matchesCount));

// --- команды от приложения ---
window.EasyPDF = {
  goToPage(n) {
    if (!numPages) return;
    pushHistory();
    programmatic = true;
    pdfViewer.currentPageNumber = Math.min(Math.max(1, n | 0), numPages);
  },
  find(query, previous, again) {
    programmatic = true;
    eventBus.dispatch('find', {
      source: window,
      type: again ? 'again' : '',
      query: String(query),
      caseSensitive: false,
      entireWord: false,
      highlightAll: true,
      findPrevious: !!previous,
      matchDiacritics: false,
    });
  },
  findClose() {
    eventBus.dispatch('findbarclose', { source: window });
  },
  // Пока открыт поиск, панель не прячется, а документ начинается под ней —
  // иначе найденное слово могло бы оказаться под панелью.
  setSearchMode(on) {
    on = !!on;
    if (on === searchMode) return;
    programmatic = true;
    if (on) {
      if (!barShown) { barShown = true; call('onBar', true); }
      searchMode = true;
      container.style.top = topInset + 'px';
      container.scrollTop += topInset;
    } else {
      container.style.top = '0';
      container.scrollTop -= topInset;
      searchMode = false;
    }
    lastTop = container.scrollTop;
  },
  back() {
    const entry = history.pop();
    call('onHistory', history.length);
    if (!entry) return;
    programmatic = true;
    if (Math.abs(entry.scale - pdfViewer.currentScale) > 0.001) {
      pdfViewer.currentScale = entry.scale;
      fitWidth = Math.abs(entry.scale - fitScale) < 0.001;
    }
    container.scrollTop = entry.top;
    container.scrollLeft = entry.left;
  },
  rotate() {
    if (!numPages) return;
    clearHistory();
    programmatic = true;
    const page = pdfViewer.currentPageNumber;
    pdfViewer.pagesRotation = (pdfViewer.pagesRotation + 90) % 360;
    applyFit();
    fitWidth = true;
    pdfViewer.currentPageNumber = page;
    reportView();
  },
  setNight(on) {
    document.body.classList.toggle('night', !!on);
  },
  goToOutline(index) {
    const dest = outlineDests[index];
    if (dest == null) return;
    programmatic = true;
    linkService.goToDestination(dest);
  },
  password(value) {
    const cb = passwordCallback;
    passwordCallback = null;
    if (cb) cb(String(value));
  },
};

// --- оглавление (закладки PDF) ---
const outlineDests = [];
async function loadOutline(doc) {
  const flat = [];
  try {
    const walk = (items, depth) => {
      for (const item of items || []) {
        if (flat.length >= 3000) return;
        flat.push({ t: String(item.title || '').trim(), d: depth });
        outlineDests.push(item.dest ?? null);
        walk(item.items, depth + 1);
      }
    };
    walk(await doc.getOutline(), 0);
  } catch (e) { /* оглавления нет или оно повреждено */ }
  call('onOutline', JSON.stringify(flat));
}

// --- загрузка документа ---
eventBus.on('pagesinit', () => {
  if (startRotation) pdfViewer.pagesRotation = startRotation;
  applyFit();
  if (Math.abs(startZoom - 1) > 0.02) {
    fitWidth = false;
    pdfViewer.currentScale = Math.min(Math.max(fitScale * startZoom, fitScale * 0.5), Math.max(fitScale * 8, 4));
  }
  if (startPage > 1) pdfViewer.currentPageNumber = Math.min(startPage, numPages);
  statusBox.classList.add('hidden');
  call('onLoaded', numPages);
  queueReport();
});

try {
  const task = pdfjsLib.getDocument({
    url: docUrl,
    disableRange: !useRange,
    disableStream: true,
    disableAutoFetch: useRange,
    rangeChunkSize: 262144,
    wasmUrl: new URL('./lib/wasm/', location.href).href,
    enableXfa: false,
  });
  task.onPassword = (callback, reason) => {
    passwordCallback = callback;
    call('onPassword', reason === pdfjsLib.PasswordResponses.INCORRECT_PASSWORD);
  };
  task.onProgress = ({ loaded, total }) => {
    if (total > 0) statusText.textContent = Math.min(100, Math.round(loaded * 100 / total)) + ' %';
  };
  const doc = await task.promise;
  numPages = doc.numPages;
  pdfViewer.setDocument(doc);
  linkService.setDocument(doc, null);
  findController.setDocument(doc);
  loadOutline(doc);
} catch (e) {
  statusBox.classList.add('hidden');
  call('onError', String((e && e.message) || e));
}
