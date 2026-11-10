// Просмотрщик EasyPDF: pdf.js + готовые компоненты PDFViewer / поиск.
// С Android общается через window.Android (JS → приложение) и window.EasyPDF (приложение → JS).
import * as pdfjsLib from './lib/pdf.min.mjs';

globalThis.pdfjsLib = pdfjsLib;
pdfjsLib.GlobalWorkerOptions.workerSrc = './lib/pdf.worker.min.mjs';
const { EventBus, PDFViewer, PDFLinkService, PDFFindController } = await import('./lib/pdf_viewer.mjs');

const noop = () => {};
const host = window.Android || {
  onLoaded: noop, onPages: noop, onError: noop, onPassword: noop, onFind: noop, onProgress: noop,
};

const params = new URLSearchParams(location.search);
const startPage = Math.max(1, parseInt(params.get('page') || '1', 10) || 1);
const docUrl = params.get('doc') || '/doc/current.pdf';

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
    host.onPages(first, last);
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
container.addEventListener('touchend', (e) => {
  if (e.touches.length !== 0 || e.changedTouches.length !== 1) { lastTap = null; return; }
  const t = e.changedTouches[0];
  const now = performance.now();
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
  }
}, { passive: true });
container.addEventListener('touchmove', () => { lastTap = null; }, { passive: true });

window.addEventListener('resize', () => {
  if (!numPages) return;
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
  host.onFind(count ? count.current || 0 : 0, count ? count.total || 0 : 0, state);
}
eventBus.on('updatefindmatchescount', ({ matchesCount }) => reportFind(-1, matchesCount));
eventBus.on('updatefindcontrolstate', ({ state, matchesCount }) => reportFind(state, matchesCount));

// --- команды от приложения ---
window.EasyPDF = {
  goToPage(n) {
    if (!numPages) return;
    pdfViewer.currentPageNumber = Math.min(Math.max(1, n | 0), numPages);
  },
  find(query, previous, again) {
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
  password(value) {
    const cb = passwordCallback;
    passwordCallback = null;
    if (cb) cb(String(value));
  },
};

// --- загрузка документа ---
eventBus.on('pagesinit', () => {
  applyFit();
  if (startPage > 1) pdfViewer.currentPageNumber = Math.min(startPage, numPages);
  statusBox.classList.add('hidden');
  host.onLoaded(numPages);
  queueReport();
});

try {
  const task = pdfjsLib.getDocument({
    url: docUrl,
    disableRange: true,
    disableStream: true,
    wasmUrl: new URL('./lib/wasm/', location.href).href,
    enableXfa: false,
  });
  task.onPassword = (callback, reason) => {
    passwordCallback = callback;
    host.onPassword(reason === pdfjsLib.PasswordResponses.INCORRECT_PASSWORD);
  };
  task.onProgress = ({ loaded, total }) => {
    if (total > 0) statusText.textContent = Math.min(100, Math.round(loaded * 100 / total)) + ' %';
  };
  const doc = await task.promise;
  numPages = doc.numPages;
  pdfViewer.setDocument(doc);
  linkService.setDocument(doc, null);
  findController.setDocument(doc);
} catch (e) {
  statusBox.classList.add('hidden');
  host.onError(String((e && e.message) || e));
}
