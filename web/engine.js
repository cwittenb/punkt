/* ============================================================================
 * Begleitungs-Engine für „.“
 *
 * Eine Übung ist eine Folge von Schritten. Jeder Schritt hat genau einen
 * Treiber (zeit, atem, halten, frei), optionale Ansagen/Cues und optionale
 * Voraussetzungen (Gates: Display unten, App sichtbar). Es gibt genau einen
 * Taktgeber, genau einen Zustandsautomaten und genau eine Sprachwarteschlange.
 *
 * Was damit verschwindet:
 *   tick / pacerTick / stufeEnde / sesWeiter / stepFertig / stepLaeuft /
 *   brauchtLage / bwStufeStart / ATEM / SES.atemAus / SES.cue / SES.cueH
 *
 * Grundsätze
 *   1. Eine Uhr: Schrittzeit = Summe der Laufabschnitte (monotone Uhr),
 *      nie "elapsed += dt". Atemphasen leiten sich aus der Schrittzeit ab,
 *      nicht aus Date.now(). Dadurch können Pacer und Timer nicht
 *      auseinanderlaufen.
 *   2. Ein Übergang: Jede Zustandsänderung läuft durch dispatch(). Während
 *      eines Übergangs eingehende Ereignisse werden gepuffert, nicht
 *      verschachtelt. Jeder Schritt bekommt ein Token; Rückrufe (TTS fertig,
 *      Timer, Musik) mit altem Token werden verworfen.
 *   3. Pause ist ein Zustand, kein Nebeneffekt: paused(grund). Solange ein
 *      Gate zu ist, steht die Uhr. Hintergrund = Pause.
 *   4. Ansagen in einer Warteschlange mit Priorität: ansage > cue > atemwort.
 *      Nur ein Schrittwechsel darf flushen.
 *   5. Die Oberfläche liest nur state(); sie schreibt nie in die Engine.
 *      Volles Rendern nur bei Schrittwechsel, sonst patch() auf feste IDs.
 *
 * Abhängigkeiten werden injiziert (Uhr, Sprache, Ton, Speicher, Nativ), damit
 * die Engine im Browser, in der App und im Test identisch läuft.
 * ========================================================================== */
'use strict';

/* ---------- Treiber ---------- */
/**
 * Jeder Treiber beantwortet drei Fragen für die Schrittzeit t (Sekunden):
 *   dauer(step)      -> Sollzeit in Sekunden oder null (offen)
 *   fertig(step, t)  -> Schritt darf enden (true/false)
 *   signale(step, t, letztes) -> Array von Signalen seit "letztes" (z. B. Atemphasen)
 */
const TREIBER = {
  // Feste Dauer. ende:'auto' geht von selbst weiter, 'tap' wartet auf Tippen.
  zeit: {
    dauer: s => s.sek,
    fertig: (s, t) => t >= s.sek,
    signale: () => [],
  },
  // Atemtakt. Endet nur am Beginn eines Einatmens, frühestens bei sek - 0.3*takt.
  atem: {
    dauer: s => s.sek,
    fertig(s, t) {
      const takt = s.takt / 1000;
      const idx = Math.floor(t / takt);
      const amEin = idx % 2 === 0 && t - idx * takt < 0.25;   // gerade umgeschlagen
      return t >= s.sek - takt * 0.3 && amEin && idx > 0;
    },
    signale(s, t, letzt) {
      const takt = s.takt / 1000;
      const idx = Math.floor(t / takt);
      if (idx === letzt.idx) return [];
      letzt.idx = idx;
      return [{ art: 'atem', wort: idx % 2 === 0 ? 'ein' : 'aus', takt: s.takt }];
    },
  },
  // Offen, endet durch Tippen oder bei max.
  halten: {
    dauer: s => null,
    fertig: (s, t) => s.max != null && t >= s.max,
    signale: () => [],
  },
  // Frei: nur Tippen.
  frei: {
    dauer: () => null,
    fertig: () => false,
    signale: () => [],
  },
};

/* ---------- Schritt-Bausteine ---------- */
function schritt(o) {
  return Object.assign({
    treiber: 'zeit', sek: 60, ende: 'tap',      // ende: 'auto' | 'tap'
    gates: [],                                   // z. B. ['lage']
    ansage: null,                                // Text zu Beginn
    cues: [],                                    // [{at: Sek | 'ende-10' | 'alle-180', text}]
    musik: null,                                 // 'start' | 'stop' | null
    warm: true,                                  // Ankommen vor dem Schritt
  }, o);
}

/* ---------- Formate ---------- */
/** Formate liefern reine Daten: Titel + Schrittliste. Keine Logik. */
const FORMATE = {
  kohaerenz: ({ min = 5, takt = 5000, begleitung = 'stumm' } = {}) => ({
    id: 'kohaerenz', titel: 'Kohärenzatmung',
    schritte: [schritt({
      key: 'kohaerenz', titel: 'Kohärenzatmung', text: 'Fünf Sekunden ein, fünf aus. Aufmerksamkeit im Brustraum.',
      treiber: 'atem', takt, sek: min * 60, gates: begleitung === 'stumm' ? ['lage'] : [],
    })],
  }),

  herzkohaerenz: ({ min = 5, takt = 5000 } = {}) => ({
    id: 'herzkohaerenz', titel: 'Herzkohärenz',
    schritte: [
      schritt({ key: 'ankommen', titel: 'Ankommen', text: 'Hand aufs Herz. Drei ruhige Atemzüge.', treiber: 'zeit', sek: 20, ende: 'auto', warm: false }),
      schritt({
        key: 'herz', titel: 'Herzatmung', text: 'Atme durch das Herz. Gleichmäßig, weich.',
        treiber: 'atem', takt, sek: min * 60, ende: 'auto',
        cues: [{ at: 'alle-120', text: 'Weich. Durch das Herz.' }],
      }),
      schritt({ key: 'nach', titel: 'Nachspüren', text: 'Was ist jetzt da?', treiber: 'zeit', sek: 60 }),
    ],
  }),

  breathworkVerbunden: ({ atmen = 25, musik = false } = {}) => ({
    id: 'breathwork', titel: 'Breathwork',
    schritte: [
      schritt({ key: 'bwVor', titel: 'Vorbereitung', treiber: 'frei', ende: 'auto',
        ansage: 'Leg dich bequem hin. Decke bereit, Augen zu, wenn du soweit bist.' }),
      schritt({ key: 'bwAtmen', titel: 'Verbundenes Atmen', treiber: 'atem', takt: 3000, sek: atmen * 60,
        ende: 'auto', musik: musik ? 'start' : null, warm: false,
        ansage: 'Verbundenes Atmen. Ein und aus, ohne Pause dazwischen. Tief, nicht schnell.',
        cues: [{ at: 'alle-180', text: ['Weiter, ohne Pause. Tiefer als schnell.', 'Lass den Körper atmen. Was kommt, darf kommen.',
          'Kribbeln und Wärme sind in Ordnung. Wird es zu viel, atme normal.', 'Nichts festhalten. Ein, aus.', 'Spür, wer das alles bemerkt.'] }] }),
      schritt({ key: 'bwRuhe', titel: 'Ruhe', treiber: 'zeit', sek: Math.max(5, Math.round(atmen / 3)) * 60, ende: 'auto', warm: false,
        ansage: 'Loslassen. Ruhe. Atme jetzt ganz normal. Liegen bleiben, nichts tun.' }),
      schritt({ key: 'bwNach', titel: 'Nachspüren', treiber: 'zeit', sek: 180, musik: 'stop', warm: false,
        ansage: 'Nachspüren. Was ist jetzt da? Und was davon war schon vorher da?' }),
    ],
  }),

  breathworkRunden: ({ runden = 3, atemzuege = 30, musik = false } = {}) => {
    const HALTEN_CUES = ['Wer bemerkt diese Stille?', 'Nichts tun. Nur da sein.', 'Ist das, was die Stille bemerkt, selbst still?'];
    const s = [schritt({ key: 'bwVor', titel: 'Vorbereitung', treiber: 'frei', ende: 'auto',
      ansage: 'Leg dich bequem hin. Decke bereit, Augen zu, wenn du soweit bist.' })];
    for (let k = 1; k <= runden; k++) {
      s.push(schritt({ key: 'bwSchnell', runde: k, titel: 'Runde ' + k + ' · Atmen', treiber: 'atem', takt: 1000, sek: atemzuege * 2,
        ende: 'auto', warm: false, musik: k === 1 && musik ? 'start' : null,
        ansage: (k > 1 ? 'Loslassen. ' : '') + 'Runde ' + k + '. Tief ein durch den Bauch, locker aus. ' + atemzuege + ' Atemzüge.',
        cues: [{ at: 'ende-10', text: 'Noch fünf.' }] }));
      s.push(schritt({ key: 'bwHalten', runde: k, titel: 'Runde ' + k + ' · Halten', treiber: 'halten', max: 180, ende: 'auto', warm: false,
        ansage: 'Ganz ausatmen. Und halten.', cues: [{ at: 20, text: HALTEN_CUES[(k - 1) % HALTEN_CUES.length] }] }));
      s.push(schritt({ key: 'bwErholung', runde: k, titel: 'Runde ' + k + ' · Erholung', treiber: 'zeit', sek: 15, ende: 'auto', warm: false,
        ansage: 'Tief einatmen. Und halten.' }));
    }
    s.push(schritt({ key: 'bwRuhe', titel: 'Ruhe', treiber: 'zeit', sek: 300, ende: 'auto', warm: false,
      ansage: 'Loslassen. Ruhe. Atme jetzt ganz normal. Liegen bleiben, nichts tun.' }));
    s.push(schritt({ key: 'bwNach', titel: 'Nachspüren', treiber: 'zeit', sek: 180, musik: 'stop', warm: false,
      ansage: 'Nachspüren. Was ist jetzt da? Und was davon war schon vorher da?' }));
    return { id: 'breathwork', titel: 'Breathwork · Runden', schritte: s };
  },
};

/* ---------- Sprachwarteschlange ---------- */
/**
 * Eine Warteschlange, drei Prioritäten. Ein Atemwort wird verworfen, wenn
 * gerade etwas Wichtigeres läuft. Ein Cue wartet. Eine Ansage wartet auch,
 * aber flush() zum Schrittwechsel räumt alles weg.
 * sprecher = { say(text) -> Promise<void>, stop() }
 */
function sprachQueue(sprecher) {
  const PRIO = { ansage: 3, cue: 2, atem: 1 };
  let laufend = null, warte = [];
  async function naechste() {
    if (laufend || !warte.length) return;
    laufend = warte.shift();
    try { await sprecher.say(laufend.text); } catch (e) {}
    laufend = null;
    naechste();
  }
  return {
    sag(text, prio) {
      if (prio === 'atem' && (laufend || warte.length)) return;        // Atemwort nie stapeln
      if (prio === 'cue' && laufend && laufend.prio === 'ansage') { warte.push({ text, prio }); return naechste(); }
      warte.push({ text, prio });
      warte.sort((a, b) => PRIO[b.prio] - PRIO[a.prio]);
      naechste();
    },
    flush() { warte = []; laufend = null; try { sprecher.stop(); } catch (e) {} },
  };
}

/* ---------- Engine ---------- */
/**
 * createEngine(deps)
 *   deps.uhr      () -> ms, monoton (performance.now)
 *   deps.sprecher { say(text)->Promise, stop() }
 *   deps.ton      { atem(wort, takt), gong(), klick(), warmEnde() }
 *   deps.musik    { start(), stop(fadeMs) }
 *   deps.wach     (an:boolean)
 *   deps.speicher { lesen()->snapshot|null, schreiben(snapshot|null) }
 *   deps.render   (state, grund)  volles Rendern ('schritt', 'phase', 'ende')
 *   deps.patch    (state)         kleine Aktualisierung (Zeit, Ring, Wort)
 *   deps.log      (text)
 */
function createEngine(deps) {
  const Q = sprachQueue(deps.sprecher);
  const WARM_ERSTER = 10, WARM_SPAETER = 5;

  // --- Zustand (nur hier wird geschrieben) ---
  let st = leer();
  function leer() {
    return {
      phase: 'idle',          // idle | warm | running | paused | stepDone | finished
      format: null, i: 0, token: 0,
      acc: 0, laufSeit: null, // Schrittzeit: acc + (jetzt - laufSeit)
      warmAcc: 0, warmSeit: null, warmDauer: 0,
      pauseGrund: null,       // 'lage' | 'hintergrund' | 'nutzer'
      gates: { lage: false, sichtbar: true },
      cuesFertig: {},         // "idx:cueNr" -> true (idempotent)
      letztSignal: {},        // Treiber-Zustand pro Schritt-Token
      halten: [],             // Sekunden je Runde
      gestartet: null, minuten: 0,
    };
  }
  const jetzt = () => deps.uhr() / 1000;
  const schrittAkt = () => st.format ? st.format.schritte[st.i] : null;
  const treiber = s => TREIBER[s.treiber];
  function zeit() { return st.acc + (st.laufSeit != null ? jetzt() - st.laufSeit : 0); }
  function warmZeit() { return st.warmAcc + (st.warmSeit != null ? jetzt() - st.warmSeit : 0); }

  // --- Übergangsschutz: kein Verschachteln, kein Doppeltippen ---
  let imUebergang = false, puffer = [];
  function dispatch(ev) {
    puffer.push(ev);
    if (imUebergang) return;
    imUebergang = true;
    try { while (puffer.length) handle(puffer.shift()); }
    finally { imUebergang = false; }
  }

  function handle(ev) {
    const s = schrittAkt();
    switch (ev.typ) {
      case 'start': return starten(ev.format, ev.extra);
      case 'tap': return tap();
      case 'gate': st.gates[ev.name] = !!ev.wert; return gatesPruefen();
      case 'pause': return pausieren('nutzer');
      case 'weiter': if (st.phase === 'paused' && st.pauseGrund === 'nutzer') return laufen(); return;
      case 'warmSkip': if (st.phase === 'warm') return schrittBeginnen(); return;
      case 'abbruch': return beenden(false);
      case 'tick': return tick();
      case 'restore': return wiederherstellen(ev.snapshot);
    }
  }

  // --- Lebenszyklus ---
  function starten(format, extra) {
    st = leer();
    st.format = format; st.gestartet = Date.now();
    Object.assign(st, extra || {});
    deps.wach(true);
    takt(true);
    deps.log('Übung gestartet: ' + format.id);
    ankommen(true);
  }

  function ankommen(erster) {
    const s = schrittAkt();
    if (!s.warm) return schrittBeginnen();
    st.phase = 'warm'; st.warmAcc = 0; st.warmSeit = jetzt();
    const w = String(s.text || '').split(/\s+/).filter(Boolean).length;
    st.warmDauer = Math.min(20, Math.max(erster ? WARM_ERSTER : WARM_SPAETER, Math.ceil(w / 3) + (erster ? 5 : 3)));
    deps.render(state(), 'schritt');
  }

  function schrittBeginnen() {
    const s = schrittAkt();
    st.token++;
    st.acc = 0; st.laufSeit = null; st.warmSeit = null;
    st.letztSignal = { idx: -1 };
    Q.flush();
    if (st.phase === 'warm') deps.ton.warmEnde();
    if (s.ansage) Q.sag(s.ansage, 'ansage');
    if (s.musik === 'start') deps.musik.start();
    if (s.musik === 'stop') deps.musik.stop(8000);
    st.phase = 'paused'; st.pauseGrund = null;
    gatesPruefen();                      // entscheidet running / paused
    deps.render(state(), 'schritt');
    speichern();
    deps.log('Schritt ' + (st.i + 1) + ' von ' + st.format.schritte.length + ': ' + s.titel);
  }

  function gatesPruefen() {
    if (!['running', 'paused'].includes(st.phase)) return;
    const s = schrittAkt();
    const zu = s.gates.find(g => !st.gates[g]);
    if (!st.gates.sichtbar) return pausieren('hintergrund');
    if (zu) return pausieren(zu);
    if (st.pauseGrund !== 'nutzer') laufen();
  }
  function pausieren(grund) {
    if (st.phase === 'running') { st.acc = zeit(); st.laufSeit = null; }
    if (st.phase === 'warm') { st.warmAcc = warmZeit(); st.warmSeit = null; }
    if (st.phase === 'running' || st.phase === 'paused') { st.phase = 'paused'; st.pauseGrund = grund; deps.render(state(), 'phase'); }
  }
  function laufen() {
    if (st.phase === 'paused') { st.phase = 'running'; st.pauseGrund = null; st.laufSeit = jetzt(); deps.render(state(), 'phase'); }
  }

  function tap() {
    const s = schrittAkt();
    if (!s) return;
    if (st.phase === 'stepDone') return weiter();
    if (st.phase === 'warm') return;                        // Warm-up nur über warmSkip
    if (s.treiber === 'halten') { st.halten[(s.runde || 1) - 1] = Math.round(zeit()); return schrittEnde(); }
    if (s.treiber === 'frei') return schrittEnde();
    // Zeit-/Atemschritt: Tippen zählt erst, wenn der Treiber fertig ist
    if (treiber(s).fertig(s, zeit())) return schrittEnde();
  }

  function schrittEnde() {
    const s = schrittAkt();
    const t = zeit();
    if (st.phase === 'running' || st.phase === 'paused') { st.acc = t; st.laufSeit = null; }
    const soll = treiber(s).dauer(s);
    st.minuten += (soll != null ? Math.min(t, soll) : t) / 60;
    const letzter = st.i === st.format.schritte.length - 1;
    if (letzter) { deps.ton.gong(); return beenden(true); }
    if (s.ende === 'auto') { deps.ton.klick(); return weiter(); }
    st.phase = 'stepDone'; deps.ton.gong(); deps.render(state(), 'phase'); speichern();
  }

  function weiter() {
    st.i++;
    ankommen(false);
    if (st.phase !== 'warm') return;      // schrittBeginnen hat gerendert
    speichern();
  }

  function beenden(regulaer) {
    const f = st.format;
    takt(false); Q.flush(); deps.wach(false); deps.musik.stop(regulaer ? 2000 : 500);
    const ergebnis = { format: f.id, minuten: Math.round(st.minuten * 10) / 10, halten: st.halten.filter(x => x != null), regulaer };
    st = leer(); st.phase = 'finished'; st.ergebnis = ergebnis;
    deps.speicher.schreiben(null);
    deps.log((regulaer ? 'Übung fertig: ' : 'Übung abgebrochen: ') + f.id);
    deps.render(state(), 'ende');
  }

  // --- Taktgeber: ein Intervall, alles aus Zeitstempeln ---
  let timer = null;
  function takt(an) {
    if (an && !timer) timer = setInterval(() => dispatch({ typ: 'tick' }), 100);
    if (!an && timer) { clearInterval(timer); timer = null; }
  }
  function tick() {
    if (st.phase === 'warm') {
      if (warmZeit() >= st.warmDauer) return schrittBeginnen();
      return deps.patch(state());
    }
    if (st.phase !== 'running') return;
    const s = schrittAkt(), t = zeit(), tok = st.token;
    // Signale (Atemphasen) — abgeleitet aus der Schrittzeit, idempotent pro Index
    for (const sig of treiber(s).signale(s, t, st.letztSignal)) {
      if (sig.art === 'atem') { deps.ton.atem(sig.wort, sig.takt); if (deps.atemWort) Q.sag(sig.wort, 'atem'); }
    }
    // Cues — genau einmal je Schritt und Cue
    const soll = treiber(s).dauer(s);
    s.cues.forEach((c, n) => {
      let at = c.at, key = st.i + ':' + n;
      if (typeof at === 'string' && at.startsWith('ende-')) { if (soll == null || soll <= 20) return; at = soll - Number(at.slice(5)); }
      if (typeof at === 'string' && at.startsWith('alle-')) {
        const jede = Number(at.slice(5)), k = Math.floor(t / jede); key += ':' + k;
        if (k < 1 || st.cuesFertig[key]) return;
        st.cuesFertig[key] = true;
        const txt = Array.isArray(c.text) ? c.text[(k - 1) % c.text.length] : c.text;
        return Q.sag(txt, 'cue');
      }
      if (t >= at && !st.cuesFertig[key]) { st.cuesFertig[key] = true; Q.sag(c.text, 'cue'); }
    });
    if (st.token !== tok) return;         // ein Cue-Rückruf hat gewechselt — nichts mehr tun
    // Ende
    if (treiber(s).fertig(s, t)) return schrittEnde();
    deps.patch(state());
    if (Date.now() - (st.gespeichert || 0) > 5000) speichern();
  }

  // --- Persistenz: nur Daten, kein DOM, kein Token ---
  function snapshot() {
    if (!st.format) return null;
    return { formatId: st.format.id, format: st.format, i: st.i, acc: zeit(), halten: st.halten, minuten: st.minuten, at: Date.now() };
  }
  function speichern() { st.gespeichert = Date.now(); try { deps.speicher.schreiben(snapshot()); } catch (e) {} }
  function wiederherstellen(g) {
    st = leer(); st.format = g.format; st.i = g.i; st.halten = g.halten || []; st.minuten = g.minuten || 0;
    deps.wach(true); takt(true);
    st.token++; st.acc = g.acc || 0; st.letztSignal = { idx: -1 }; st.phase = 'paused';
    gatesPruefen(); deps.render(state(), 'schritt');
    deps.log('Übung fortgesetzt: ' + g.formatId + ' · Schritt ' + (g.i + 1));
  }

  // --- Lesezugriff für die Oberfläche ---
  function state() {
    const s = schrittAkt();
    const soll = s ? treiber(s).dauer(s) : null, t = s ? zeit() : 0;
    return {
      phase: st.phase, pauseGrund: st.pauseGrund, i: st.i, n: st.format ? st.format.schritte.length : 0,
      titel: st.format ? st.format.titel : '', schritt: s, zeit: t, soll,
      rest: soll != null ? Math.max(0, soll - t) : null, anteil: soll ? Math.min(1, t / soll) : (t % 60) / 60,
      warmRest: st.phase === 'warm' ? Math.max(0, st.warmDauer - warmZeit()) : 0, warmAnteil: st.warmDauer ? Math.min(1, warmZeit() / st.warmDauer) : 0,
      atemWort: st.letztSignal.idx >= 0 ? (st.letztSignal.idx % 2 === 0 ? 'ein' : 'aus') : '',
      fertig: st.phase === 'stepDone', letzter: st.format ? st.i === st.format.schritte.length - 1 : false,
      halten: st.halten, ergebnis: st.ergebnis || null,
    };
  }

  return {
    start: (format, extra) => dispatch({ typ: 'start', format, extra }),
    tap: () => dispatch({ typ: 'tap' }),
    gate: (name, wert) => dispatch({ typ: 'gate', name, wert }),
    pause: () => dispatch({ typ: 'pause' }),
    weiter: () => dispatch({ typ: 'weiter' }),
    warmSkip: () => dispatch({ typ: 'warmSkip' }),
    abbruch: () => dispatch({ typ: 'abbruch' }),
    restore: snapshot => dispatch({ typ: 'restore', snapshot }),
    state, snapshot,
    laeuft: () => !!st.format && st.phase !== 'finished',
  };
}

/* ---------- Anbindung an die bestehende App (Skizze) ----------
const ENGINE = createEngine({
  uhr: () => performance.now(),
  sprecher: {
    // Nativ: TTS mit QUEUE_ADD und UtteranceProgressListener -> Promise erfüllen, wenn fertig.
    // Fallback: speechSynthesis mit onend. Wichtig: erst sprechen, wenn TTS "bereit" meldet,
    // sonst geht die erste Ansage verloren (aktuell der Fall).
    say: text => NATIVE ? nativSag(text) : webSag(text),
    stop: () => NATIVE ? nat('sprichStopp') : speechSynthesis.cancel(),
  },
  ton: { atem: (w, takt) => atemTon(w, takt), gong, klick: () => ton(528, 528, .9, .08), warmEnde: () => ton(330, 330, 1, .12) },
  musik: { start: () => nat('musikStart', 0.5), stop: ms => nat('musikStopp', ms) },
  wach: an => an ? wach() : schlaf(),
  speicher: { lesen: () => JSON.parse(localStorage.getItem(SES_KEY) || 'null'), schreiben: s => s ? localStorage.setItem(SES_KEY, JSON.stringify(s)) : localStorage.removeItem(SES_KEY) },
  render: (z, grund) => { if (grund === 'ende') sesFertig(z.ergebnis); else render(); },
  patch: z => { setText('s-zeit', mmss(z.rest != null ? z.rest : z.zeit)); setRing('s-ring', z.anteil); setPacer(z.atemWort); },
  log: plog,
});
// Gates speisen:  window.punktNativ.lage = b => ENGINE.gate('lage', !!b)
//                 document.addEventListener('visibilitychange', () => ENGINE.gate('sichtbar', !document.hidden))
// Aktionen:       A.sesWeiter = () => ENGINE.tap();  A.haltenEnde = () => ENGINE.tap();  A.warmSkip = () => ENGINE.warmSkip();
// Formate:        startSession('breathwork') -> ENGINE.start(b.variante==='runden' ? FORMATE.breathworkRunden(b) : FORMATE.breathworkVerbunden(b))
// Bestehende Pläne (ankerPlan/hauptPlan) lassen sich 1:1 in schritt({...}) überführen:
//   st('kohaerenz',2) -> schritt({treiber:'atem', takt:5000, sek:120, gates:['lage']})
//   st('koerper',10)  -> schritt({treiber:'zeit', sek:600, gates:['lage']})
--------------------------------------------------------------- */

if (typeof module !== 'undefined') module.exports = { createEngine, FORMATE, TREIBER, schritt, sprachQueue };
