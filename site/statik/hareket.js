// Kairo Mesh sitesi: kaydırmaya bağlı hareket. Bağımlılık yok; kaydırmaya müdahale etmez (yalnız okur).
// "Hareketi azalt" açıksa hiçbir şey hareket etmez: sahneler son hâliyle, sayılar son değeriyle görünür.
(() => {
  const azalt = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const kok = document.documentElement;
  window.kairoHareket = true;
  kok.classList.add('js');
  if (azalt) kok.classList.add('az-hareket');
  const sinirla = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
  const aralik = (p, a, b) => sinirla((p - a) / (b - a)); // p'nin [a,b] içindeki yeri, 0..1
  const yumusak = (t) => 1 - Math.pow(1 - t, 3);

  // --- 1. Görünce açılan öğeler (başlık satırları, kartlar, sayılar) -------------------------------
  const goster = (el) => {
    el.classList.add('gorundu');
    if (el.dataset.sayi) sayac(el);
  };
  const io = !azalt && 'IntersectionObserver' in window
    ? new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { goster(e.target); io.unobserve(e.target); } }),
      { rootMargin: '0px 0px -12% 0px', threshold: 0.15 })
    : null;
  // açılış bölümü hariç: ilk ekrandaki başlık hiç gizlenmez, CSS giriş animasyonuyla gelir
  document.querySelectorAll('main h1, main h2, .kart, .sayi, .adimlar li, .cizelge, .tablo, .zaman li, [data-goster], [data-sayi]')
    .forEach((el) => {
      if (el.closest('.acilis, .yolculuk')) return; // yapışkan sahne kendi hareketini yönetir
      el.classList.add('acilir');
      if (io) io.observe(el); else goster(el);
    });

  // sayılar: 0'dan hedefe (Türkçe biçim: 2.451, %64,8)
  function sayac(el) {
    const hedef = parseFloat(el.dataset.sayi), ondalik = +(el.dataset.ondalik || 0);
    const yaz = (v) => (el.dataset.on || '') + v.toLocaleString('tr-TR', { minimumFractionDigits: ondalik, maximumFractionDigits: ondalik }) + (el.dataset.son || '');
    if (azalt) { el.textContent = yaz(hedef); return; }
    const t0 = performance.now(), sure = 1400;
    const adim = (t) => {
      const k = yumusak(sinirla((t - t0) / sure));
      el.textContent = yaz(hedef * k);
      if (k < 1) requestAnimationFrame(adim);
    };
    requestAnimationFrame(adim);
  }

  // --- 2. Koyu bölümlerde üst çubuk da koyulaşır ---------------------------------------------------
  const ust = document.querySelector('.ust');
  const koyular = document.querySelectorAll('[data-tema="koyu"]');
  if (ust && koyular.length && 'IntersectionObserver' in window) {
    const acik = new Set();
    // üst çubuğun hizasındaki ince bant: koyu bölüm o bantta mı?
    const bant = new IntersectionObserver((es) => {
      es.forEach((e) => (e.isIntersecting ? acik.add(e.target) : acik.delete(e.target)));
      ust.classList.toggle('koyu', acik.size > 0);
    }, { rootMargin: '0px 0px -92% 0px', threshold: 0 });
    koyular.forEach((k) => bant.observe(k));
  }
  addEventListener('scroll', () => ust && ust.classList.toggle('kaydi', scrollY > 8), { passive: true });

  if (azalt) return;

  // --- 3. Sönümlü kaydırma ilerlemesi: [data-sahne] öğelerine --p (0..1) ----------------------------
  // Gerçek kaydırma okunur, gölge değer ona yumuşakça yaklaşır (α≈0,12): hareket ivmeli değil, akıcı.
  const sahneler = [...document.querySelectorAll('[data-sahne]')].map((el) => ({ el, p: 0, hedef: 0, cb: null }));
  const kayit = (el, cb) => { const s = sahneler.find((x) => x.el === el); if (s) s.cb = cb; };
  function hedefHesapla() {
    const h = innerHeight;
    for (const s of sahneler) {
      const r = s.el.getBoundingClientRect();
      // yapışkan sahne: üst kenar ekranın üstüne geldiğinde 0, sahnenin sonu ekranın altına geldiğinde 1
      s.hedef = s.el.dataset.sahne === 'yapiskan'
        ? sinirla(-r.top / Math.max(1, r.height - h))
        : sinirla((h - r.top) / (h + r.height));
    }
  }
  function dongu() {
    hedefHesapla();
    for (const s of sahneler) {
      const once = s.p;
      s.p += (s.hedef - s.p) * 0.12;
      if (Math.abs(s.hedef - s.p) < 0.0005) s.p = s.hedef;
      if (s.p !== once || !s.ilk) {
        s.ilk = true;
        s.el.style.setProperty('--p', s.p.toFixed(4));
        if (s.cb) s.cb(s.p);
      }
    }
    requestAnimationFrame(dongu);
  }

  // --- 4. Açılış: canlı ağ (telefonlar, menzil çizgileri, kuruma atlayan çağrı) -----------------------
  const tuval = document.querySelector('canvas[data-ag]');
  if (tuval) ag(tuval);
  function ag(cv) {
    const ctx = cv.getContext('2d');
    const renk = { cizgi: 'rgba(120,200,170,', dugum: '#7fd8b6', acil: '#ff6b5e', kurum: '#f2c46b', internet: '#4fd1a1' };
    let w = 0, h = 0, dpr = 1, dugumler = [], paket = null, sonPaket = 0, gorunur = true;
    const MENZIL = () => Math.max(110, Math.min(170, w / 9));
    function kur() {
      dpr = Math.min(2, devicePixelRatio || 1);
      w = cv.clientWidth; h = cv.clientHeight;
      cv.width = w * dpr; cv.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const n = Math.round(Math.min(80, Math.max(28, (w * h) / 16000)));
      dugumler = Array.from({ length: n }, () => ({
        x: Math.random() * w, y: Math.random() * h,
        vx: (Math.random() - 0.5) * 0.18, vy: (Math.random() - 0.5) * 0.18, tur: 'tel',
      }));
      // sabit roller: sol altta enkaz altındaki telefon, sağda internete ulaşan telefon, sağ üstte kurum
      dugumler[0] = { x: w * 0.14, y: h * 0.78, vx: 0, vy: 0, tur: 'acil' };
      dugumler[1] = { x: w * 0.82, y: h * 0.42, vx: 0.05, vy: 0.03, tur: 'internet' };
      dugumler[2] = { x: w * 0.9, y: h * 0.14, vx: 0, vy: 0, tur: 'kurum' };
    }
    // o anki menzil grafiğinde enkazdan internetli telefona en kısa yol (BFS)
    function yol() {
      const m = MENZIL(), onceki = new Map([[0, -1]]), kuyruk = [0];
      while (kuyruk.length) {
        const i = kuyruk.shift();
        if (i === 1) break;
        dugumler.forEach((d, j) => {
          if (j === 2 || onceki.has(j)) return;
          if (Math.hypot(d.x - dugumler[i].x, d.y - dugumler[i].y) < m) { onceki.set(j, i); kuyruk.push(j); }
        });
      }
      if (!onceki.has(1)) return null;
      const y = [1];
      while (y[0] !== 0) y.unshift(onceki.get(y[0]));
      return [...y, 2]; // son atlama: internet üzerinden kuruma
    }
    function ciz(t) {
      if (!gorunur) { requestAnimationFrame(ciz); return; }
      ctx.clearRect(0, 0, w, h);
      const m = MENZIL();
      for (const d of dugumler) {
        if (d.tur === 'acil' || d.tur === 'kurum') continue;
        d.x += d.vx; d.y += d.vy;
        if (d.x < 0 || d.x > w) d.vx *= -1;
        if (d.y < 0 || d.y > h) d.vy *= -1;
      }
      // menzil çizgileri
      ctx.lineWidth = 1;
      for (let i = 0; i < dugumler.length; i++) for (let j = i + 1; j < dugumler.length; j++) {
        if (i === 2 || j === 2) continue;
        const a = dugumler[i], b = dugumler[j], u = Math.hypot(a.x - b.x, a.y - b.y);
        if (u < m) {
          ctx.strokeStyle = renk.cizgi + (0.38 * (1 - u / m)).toFixed(3) + ')';
          ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
        }
      }
      // çağrı paketi: ~4 saniyede bir yeni yolculuk
      if (!paket && t - sonPaket > 2600) { const y = yol(); if (y) paket = { y, t0: t }; sonPaket = t; }
      if (paket) {
        const atlama = 520, k = (t - paket.t0) / atlama, i = Math.floor(k);
        if (i >= paket.y.length - 1) { paket = null; sonPaket = t; }
        else {
          // geçilen yol yanar
          ctx.strokeStyle = 'rgba(255,107,94,0.55)'; ctx.lineWidth = 2;
          ctx.beginPath();
          for (let s = 0; s <= i; s++) { const d = dugumler[paket.y[s]]; s ? ctx.lineTo(d.x, d.y) : ctx.moveTo(d.x, d.y); }
          const a = dugumler[paket.y[i]], b = dugumler[paket.y[i + 1]], f = yumusak(k - i);
          const x = a.x + (b.x - a.x) * f, y = a.y + (b.y - a.y) * f;
          ctx.lineTo(x, y); ctx.stroke();
          const g = ctx.createRadialGradient(x, y, 0, x, y, 16);
          g.addColorStop(0, 'rgba(255,140,120,0.95)'); g.addColorStop(1, 'rgba(255,107,94,0)');
          ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, 16, 0, 7); ctx.fill();
        }
      }
      // düğümler
      for (const d of dugumler) {
        const r = d.tur === 'tel' ? 2.6 : 5.5;
        ctx.fillStyle = d.tur === 'tel' ? renk.dugum : renk[d.tur];
        ctx.globalAlpha = d.tur === 'tel' ? 0.75 : 1;
        ctx.beginPath(); ctx.arc(d.x, d.y, r, 0, 7); ctx.fill();
        if (d.tur !== 'tel') { // nabız halkası
          const n = (t / 1600 + (d.tur === 'acil' ? 0 : 0.5)) % 1;
          ctx.strokeStyle = ctx.fillStyle; ctx.globalAlpha = 1 - n; ctx.lineWidth = 1.5;
          ctx.beginPath(); ctx.arc(d.x, d.y, 6 + n * 22, 0, 7); ctx.stroke();
        }
      }
      ctx.globalAlpha = 1;
      requestAnimationFrame(ciz);
    }
    kur();
    addEventListener('resize', kur);
    new IntersectionObserver(([e]) => { gorunur = e.isIntersecting; }).observe(cv);
    requestAnimationFrame(ciz);
  }

  // --- 5. Yapışkan sahne: bir çağrının yolculuğu ---------------------------------------------------
  const yolculuk = document.querySelector('[data-sahne="yapiskan"].yolculuk');
  if (yolculuk) {
    const adimlar = yolculuk.querySelectorAll('[data-adim]');
    const q = (s) => yolculuk.querySelector(s);
    const balon = q('.y-balon'), kayitlar = yolculuk.querySelectorAll('.y-kayit'), zarf = q('.y-zarf'),
      kilit = q('.y-kilit'), hat = q('.y-hat'), kurumHat = q('.y-kurum-hat'), makbuz = q('.y-makbuz'), tamam = q('.y-tamam'),
      kurum = q('.y-kurum');
    // zarfın izlediği yol: enkazdaki telefon -> üç taşıyıcı -> internetli telefon
    const noktalar = [[70, 300], [200, 200], [320, 240], [430, 170], [520, 140]]; // index.html'deki telefonlar
    const yerinde = (k) => {
      const parca = k * (noktalar.length - 1), i = Math.min(noktalar.length - 2, Math.floor(parca)), f = parca - i;
      const [a, b] = [noktalar[i], noktalar[i + 1]];
      return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
    };
    const hatBoy = hat.getTotalLength ? hat.getTotalLength() : 600;
    hat.style.strokeDasharray = hatBoy;
    kayit(yolculuk, (p) => {
      const etkin = Math.min(3, Math.floor(p * 4));
      adimlar.forEach((a, i) => a.classList.toggle('etkin', i === etkin));
      // 1: yazılır, telefon kayda çevirir
      balon.style.opacity = 1 - aralik(p, 0.27, 0.33);
      kayitlar.forEach((k, i) => {
        const g = aralik(p, 0.08 + i * 0.04, 0.14 + i * 0.04) * (1 - aralik(p, 0.27, 0.33));
        k.style.opacity = g; k.style.transform = `translateY(${(1 - g) * 8}px)`;
      });
      // 2: mühürlenir
      const muhur = aralik(p, 0.3, 0.42);
      zarf.style.opacity = muhur * (1 - aralik(p, 0.8, 0.86)); // kuruma yüklenince ağdan çekilir
      kilit.style.transform = `translateY(${(1 - aralik(p, 0.38, 0.46)) * -10}px)`;
      kilit.style.opacity = aralik(p, 0.38, 0.46);
      // 3: taşınır
      const tasi = aralik(p, 0.5, 0.74);
      const [x, y] = yerinde(tasi);
      zarf.setAttribute('transform', `translate(${x - 70} ${y - 300})`);
      hat.style.strokeDashoffset = hatBoy * (1 - tasi);
      // 4: iletilir, makbuz döner
      const ilet = aralik(p, 0.76, 0.84);
      kurumHat.style.opacity = ilet; kurum.classList.toggle('yandi', ilet > 0.9);
      const don = aralik(p, 0.86, 0.97);
      makbuz.style.opacity = don > 0 && don < 1 ? 1 : 0;
      const [mx, my] = yerinde(1 - don);
      makbuz.setAttribute('transform', `translate(${mx} ${my})`);
      tamam.style.opacity = aralik(p, 0.95, 1);
    });
  }

  // --- 6. Çizelge: kaydırdıkça dolan çubuklar ------------------------------------------------------
  document.querySelectorAll('.cizelge').forEach((c) => {
    c.setAttribute('data-sahne', 'gorunum');
    sahneler.push({ el: c, p: 0, hedef: 0, cb: (p) => c.style.setProperty('--dolu', aralik(p, 0.15, 0.5).toFixed(3)) });
  });

  requestAnimationFrame(dongu);
})();
