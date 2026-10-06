/* ============================================================================
 * Begleitungs-Engine für „.“
 *
 * Eine Übung ist eine Folge von Schritten. Jeder Schritt hat genau einen
 * Treiber (zeit, atem, halten, frei), optionale Ansage/Cues und optionale
 * Voraussetzungen (Gates: Display unten, App sichtbar). Es gibt genau einen
 * Taktgeber, genau einen Zustandsautomaten und genau eine Sprachwarteschlange.
 *
 *   1. Eine Uhr: Schrittzeit = Summe der Laufabschnitte (monotone Uhr), nie
 *      "elapsed += dt". Atemphasen leiten sich aus der Schrittzeit ab.
 *   2. Ein Übergang: alles durch dispatch(); während eines Übergangs eingehende
 *      Ereignisse werden gepuffert. Jeder Schritt hat ein Token; Tippen kurz
 *      nach einem Wechsel wird verworfen (Doppeltipp).
 *   3. Pause ist ein Zustand mit Grund (lage, hintergrund, nutzer). Die Uhr steht.
 *   4. Sprachwarteschlange mit Priorität ansage > cue > atem. Nur ein
 *      Schrittwechsel flusht.
 *   5. Die Oberfläche liest nur state(); sie schreibt nie in die Engine.
 *
 * Diese Datei ist die Quelle; tools/web.py bettet sie in index.html ein.
 * Im Browser lädt punkt.html sie per <script src>.
 * ========================================================================== */
'use strict';

/* ---------- Treiber ---------- */
const TREIBER = {
  zeit: {
    dauer: s => s.sek,
    fertig: (s, t) => t >= s.sek,
    signale: () => [],
  },
  // Endet nur am Beginn eines Einatmens, frühestens bei sek - 0.3*takt.
  atem: {
    dauer: s => s.sek,
    fertig(s, t) {
      const takt = s.takt / 1000, idx = Math.floor(t / takt);
      const amEin = idx % 2 === 0 && t - idx * takt < 0.25;
      return idx > 0 && amEin && t >= s.sek - takt * 0.3;
    },
    signale(s, t, letzt) {
      const takt = s.takt / 1000, idx = Math.floor(t / takt);
      if (idx === letzt.idx) return [];
      letzt.idx = idx;
      return [{ art: 'atem', wort: idx % 2 === 0 ? 'ein' : 'aus', takt: s.takt }];
    },
  },
  halten: {
    dauer: () => null,
    fertig: (s, t) => s.max != null && t >= s.max,
    signale: () => [],
  },
  frei: {
    dauer: () => null,
    fertig: () => false,
    signale: () => [],
  },
};

function schritt(o) {
  return Object.assign({
    treiber: 'zeit', sek: 60, ende: 'tap',   // ende: 'auto' | 'tap'
    gates: [], ansage: null, cues: [],       // cues: [{at: Sek | 'ende-10' | 'alle-180', text}]
    musik: null, warm: true,                 // musik: 'start' | 'stop'
  }, o);
}

/* ---------- Sprachwarteschlange ---------- */
function sprachQueue(sprecher) {
  const PRIO = { ansage: 3, cue: 2, atem: 1 };
  let laufend = null, warte = [], gen = 0;
  async function naechste() {
    if (laufend || !warte.length) return;
    laufend = warte.shift();
    const g = gen;
    try { await sprecher.say(laufend.text); } catch (e) {}
    if (g !== gen) return;                   // flush dazwischen: alles verworfen
    laufend = null;
    naechste();
  }
  return {
    sag(text, prio) {
      if (prio === 'atem' && (laufend || warte.length)) return;
      warte.push({ text, prio });
      warte.sort((a, b) => PRIO[b.prio] - PRIO[a.prio]);
      naechste();
    },
    flush() { gen++; warte = []; laufend = null; try { sprecher.stop(); } catch (e) {} },
  };
}

/* ---------- Engine ---------- */
/**
 * deps.uhr () -> ms monoton · deps.sprecher {say(text)->Promise, stop()} ·
 * deps.atemWort (sig)->bool · deps.ton {atem(wort,takt), gong(), klick(), warmEnde()} ·
 * deps.musik {start(), stop(ms)} · deps.wach(an) · deps.speicher {schreiben(snapshot|null)} ·
 * deps.render(state, grund) grund: 'schritt'|'phase'|'ende' · deps.patch(state) · deps.log(text)
 */
function createEngine(deps) {
  const Q = sprachQueue(deps.sprecher);
  const WARM_ERSTER = 10, WARM_SPAETER = 5, ENTPRELL = 600;

  let st = leer();
  function leer() {
    return {
      phase: 'idle', format: null, i: 0, token: 0,
      acc: 0, laufSeit: null,
      warmAcc: 0, warmSeit: null, warmDauer: 0,
      pauseGrund: null, gates: { lage: false, sichtbar: true },
      cuesFertig: {}, letztSignal: { idx: -1 }, halten: [],
      minuten: 0, wechselAm: 0, gespeichert: 0, ergebnis: null,
    };
  }
  const jetzt = () => deps.uhr() / 1000;
  const schrittAkt = () => st.format ? st.format.schritte[st.i] : null;
  const treiber = s => TREIBER[s.treiber];
  const zeit = () => st.acc + (st.laufSeit != null ? jetzt() - st.laufSeit : 0);
  const warmZeit = () => st.warmAcc + (st.warmSeit != null ? jetzt() - st.warmSeit : 0);
  const frisch = () => Date.now() - st.wechselAm < ENTPRELL;

  let imUebergang = false, puffer = [];
  function dispatch(ev) {
    puffer.push(ev);
    if (imUebergang) return;
    imUebergang = true;
    try { while (puffer.length) handle(puffer.shift()); }
    finally { imUebergang = false; }
  }
  function handle(ev) {
    switch (ev.typ) {
      case 'start': return starten(ev.format, ev.extra);
      case 'restore': return wiederherstellen(ev.snapshot, ev.extra);
      case 'tap': return tap();
      case 'skip': return skip();
      case 'ende': return endeJetzt();
      case 'gate': st.gates[ev.name] = !!ev.wert; return gatesPruefen();
      case 'pause': return pausieren('nutzer');
      case 'weiter': if (st.phase === 'paused' && st.pauseGrund === 'nutzer') laufen(); return;
      case 'warmSkip': if (st.phase === 'warm') schrittBeginnen(); return;
      case 'format': if (st.format && st.i === 0 && st.phase === 'warm') st.format = ev.format; return;
      case 'abbruch': return beenden(false);
      case 'tick': return tick();
    }
  }

  function starten(format, extra) {
    st = leer(); st.format = format;
    Object.assign(st, extra || {});
    deps.wach(true); takt(true);
    deps.log('Übung gestartet: ' + format.id);
    ankommen(true);
  }
  function ankommen(erster) {
    const s = schrittAkt();
    if (!s.warm) return schrittBeginnen();
    st.phase = 'warm'; st.warmAcc = 0; st.warmSeit = st.gates.sichtbar ? jetzt() : null;
    const w = String(s.text || '').split(/\s+/).filter(Boolean).length;
    st.warmDauer = Math.min(20, Math.max(erster ? WARM_ERSTER : WARM_SPAETER, Math.ceil(w / 3) + (erster ? 5 : 3)));
    st.wechselAm = Date.now();
    deps.render(state(), 'schritt');
  }
  function schrittBeginnen() {
    const s = schrittAkt();
    st.token++; st.acc = 0; st.laufSeit = null; st.warmSeit = null;
    st.letztSignal = { idx: -1 }; st.wechselAm = Date.now();
    Q.flush();
    if (st.phase === 'warm') deps.ton.warmEnde();
    if (s.ansage) Q.sag(s.ansage, 'ansage');
    if (s.musik === 'start') deps.musik.start();
    if (s.musik === 'stop') deps.musik.stop(8000);
    st.phase = 'paused'; st.pauseGrund = null;
    gatesPruefen();
    deps.render(state(), 'schritt');
    speichern();
    deps.log('Schritt ' + (st.i + 1) + ' von ' + st.format.schritte.length + ': ' + s.titel);
  }
  function gatesPruefen() {
    if (st.phase === 'warm') {
      if (!st.gates.sichtbar) { if (st.warmSeit != null) { st.warmAcc = warmZeit(); st.warmSeit = null; } }
      else if (st.warmSeit == null) st.warmSeit = jetzt();
      return;
    }
    if (st.phase !== 'running' && st.phase !== 'paused') return;
    const zu = schrittAkt().gates.find(g => !st.gates[g]);
    if (!st.gates.sichtbar) return pausieren('hintergrund');
    if (zu) return pausieren(zu);
    if (st.pauseGrund !== 'nutzer') laufen();
  }
  function pausieren(grund) {
    if (st.phase === 'running') { st.acc = zeit(); st.laufSeit = null; }
    if (st.phase === 'running' || (st.phase === 'paused' && st.pauseGrund !== grund)) {
      st.phase = 'paused'; st.pauseGrund = grund; deps.render(state(), 'phase');
    }
  }
  function laufen() {
    if (st.phase !== 'paused') return;
    st.phase = 'running'; st.pauseGrund = null; st.laufSeit = jetzt();
    deps.render(state(), 'phase');
  }

  function tap() {
    const s = schrittAkt();
    if (!s) return;
    if (st.phase === 'stepDone') return weiter();      // Warten auf den Nutzer: kein Entprellen nötig
    if (frisch()) return;                              // kurz nach einem Schrittstart: Doppeltipp
    if (st.phase !== 'running' && st.phase !== 'paused') return;
    if (s.treiber === 'halten') { st.halten[(s.runde || 1) - 1] = Math.round(zeit()); return schrittEnde(); }
    if (s.treiber === 'frei') return schrittEnde();
    if (treiber(s).fertig(s, zeit())) return schrittEnde();
  }
  /** Schritt vorzeitig beenden (Breathwork „Weiter“, „Genug für heute“). */
  function skip() {
    const s = schrittAkt();
    if (!s) return;
    if (st.phase === 'stepDone') return weiter();
    if (frisch()) return;
    if (st.phase !== 'running' && st.phase !== 'paused') return;
    if (s.treiber === 'halten') st.halten[(s.runde || 1) - 1] = Math.round(zeit());
    schrittEnde(true);
  }
  function minutenZaehlen(s, t) {
    const soll = treiber(s).dauer(s);
    if (s.min != null && soll) st.minuten += s.min * Math.min(1, t / soll);
    else if (s.min != null && soll == null) st.minuten += Math.min(s.min, t / 60);
    else st.minuten += (soll != null ? Math.min(t, soll) : t) / 60;
  }
  function schrittEnde(erzwungen) {
    const s = schrittAkt(), t = zeit();
    st.acc = t; st.laufSeit = null;
    minutenZaehlen(s, t);
    const letzter = st.i === st.format.schritte.length - 1;
    if (letzter) { deps.ton.gong(); return beenden(true); }
    if (s.ende === 'auto' || erzwungen) { deps.ton.klick(); return weiter(); }
    st.phase = 'stepDone';
    deps.ton.gong(); deps.render(state(), 'phase'); speichern();
  }
  function endeJetzt() {
    const s = schrittAkt();
    if (!s) return;
    if (st.phase === 'running' || st.phase === 'paused') { const t = zeit(); st.acc = t; st.laufSeit = null; minutenZaehlen(s, t); }
    beenden(true);
  }
  function weiter() { st.i++; ankommen(false); speichern(); }
  function beenden(regulaer) {
    const f = st.format;
    if (!f) return;
    takt(false); Q.flush(); deps.wach(false); deps.musik.stop(regulaer ? 2000 : 500);
    const ergebnis = { format: f.id, minuten: Math.round(st.minuten * 10) / 10, halten: st.halten.filter(x => x != null), regulaer };
    st = leer(); st.phase = 'finished'; st.ergebnis = ergebnis;
    try { deps.speicher.schreiben(null); } catch (e) {}
    deps.log((regulaer ? 'Übung fertig: ' : 'Übung abgebrochen: ') + f.id);
    deps.render(state(), 'ende');
  }

  let timer = null;
  function takt(an) {
    if (an && !timer) timer = setInterval(() => dispatch({ typ: 'tick' }), 100);
    if (!an && timer) { clearInterval(timer); timer = null; }
  }
  function tick() {
    if (st.phase === 'warm') {
      if (st.warmSeit == null) return;
      if (warmZeit() >= st.warmDauer) return schrittBeginnen();
      return deps.patch(state());
    }
    if (st.phase !== 'running') return;
    const s = schrittAkt(), t = zeit(), tok = st.token;
    for (const sig of treiber(s).signale(s, t, st.letztSignal)) {
      if (sig.art === 'atem') { deps.ton.atem(sig.wort, sig.takt); if (deps.atemWort && deps.atemWort(sig)) Q.sag(sig.wort, 'atem'); }
    }
    const soll = treiber(s).dauer(s);
    s.cues.forEach((c, n) => {
      let at = c.at, key = st.i + ':' + n;
      if (typeof at === 'string' && at.startsWith('ende-')) { if (soll == null || soll <= 20) return; at = soll - Number(at.slice(5)); }
      else if (typeof at === 'string' && at.startsWith('alle-')) {
        const jede = Number(at.slice(5)), k = Math.floor(t / jede); key += ':' + k;
        if (k < 1 || st.cuesFertig[key]) return;
        st.cuesFertig[key] = true;
        return Q.sag(Array.isArray(c.text) ? c.text[(k - 1) % c.text.length] : c.text, 'cue');
      }
      if (t >= at && !st.cuesFertig[key]) { st.cuesFertig[key] = true; Q.sag(c.text, 'cue'); }
    });
    if (st.token !== tok) return;
    if (treiber(s).fertig(s, t)) return schrittEnde();
    deps.patch(state());
    if (Date.now() - st.gespeichert > 3000) speichern();
  }

  function snapshot() {
    if (!st.format || st.phase === 'finished') return null;
    return { format: st.format, i: st.i, acc: zeit(), halten: st.halten, minuten: st.minuten, at: Date.now() };
  }
  function speichern() { st.gespeichert = Date.now(); try { deps.speicher.schreiben(snapshot()); } catch (e) {} }
  function wiederherstellen(g, extra) {
    st = leer(); st.format = g.format; st.i = g.i; st.halten = g.halten || []; st.minuten = g.minuten || 0;
    Object.assign(st, extra || {});
    deps.wach(true); takt(true);
    st.token++; st.acc = g.acc || 0; st.phase = 'paused'; st.wechselAm = Date.now();
    gatesPruefen(); deps.render(state(), 'schritt');
    deps.log('Übung fortgesetzt: ' + g.format.id + ' · Schritt ' + (g.i + 1));
  }

  function state() {
    const s = schrittAkt();
    const soll = s ? treiber(s).dauer(s) : null, t = s ? zeit() : 0;
    return {
      phase: st.phase, pauseGrund: st.pauseGrund, i: st.i, n: st.format ? st.format.schritte.length : 0,
      schritt: s, zeit: t, soll,
      rest: soll != null ? Math.max(0, soll - t) : null,
      anteil: soll ? Math.min(1, t / soll) : (t % 60) / 60,
      warmRest: st.phase === 'warm' ? Math.max(0, st.warmDauer - warmZeit()) : 0,
      warmAnteil: st.warmDauer ? Math.min(1, warmZeit() / st.warmDauer) : 0,
      atemWort: st.letztSignal.idx >= 0 ? (st.letztSignal.idx % 2 === 0 ? 'ein' : 'aus') : '',
      fertig: st.phase === 'stepDone', letzter: !!st.format && st.i === st.format.schritte.length - 1,
      halten: st.halten, ergebnis: st.ergebnis,
    };
  }

  return {
    start: (format, extra) => dispatch({ typ: 'start', format, extra }),
    restore: (snapshot, extra) => dispatch({ typ: 'restore', snapshot, extra }),
    tap: () => dispatch({ typ: 'tap' }),
    skip: () => dispatch({ typ: 'skip' }),
    ende: () => dispatch({ typ: 'ende' }),
    gate: (name, wert) => dispatch({ typ: 'gate', name, wert }),
    pause: () => dispatch({ typ: 'pause' }),
    weiter: () => dispatch({ typ: 'weiter' }),
    warmSkip: () => dispatch({ typ: 'warmSkip' }),
    format: f => dispatch({ typ: 'format', format: f }),
    abbruch: () => dispatch({ typ: 'abbruch' }),
    state, snapshot, speichern,
    schritte: () => st.format ? st.format.schritte : [],
    laeuft: () => !!st.format && st.phase !== 'finished',
  };
}

if (typeof module !== 'undefined') module.exports = { createEngine, TREIBER, schritt, sprachQueue };
