/*!
 * Dash Motion Engine 5.0 — "Fluid"
 * ---------------------------------------------------------------------------
 * A ground-up, dependency-free motion engine for the Dash / NeoDash /
 * FabricDash / ForgeDash admin panels.
 *
 * Design rules (the reason the old GSAP layer felt rapid and jerky):
 *
 *  1. ONE owner per property. The engine owns `transform` and `opacity` and
 *     nothing else ever transitions them. Colours, shadows and borders stay in
 *     CSS. No more double-easing, no more clearProps snap-back.
 *  2. Physical springs, not duration curves. Every motion is a damped harmonic
 *     oscillator solved analytically, parameterised the way SwiftUI does it
 *     (response + dampingFraction) so retargeting mid-flight PRESERVES
 *     VELOCITY instead of cutting the animation. This is the single thing that
 *     makes iOS motion feel continuous.
 *  3. Compositor-only. translate3d / scale / rotate / opacity. Never width,
 *     never box-shadow, never background-color, never top/left.
 *  4. One rAF ticker, batched reads then batched writes. No layout thrash.
 *  5. Off-screen work is skipped (IntersectionObserver), work is capped, and
 *     containers animate instead of 200 individual children.
 *  6. prefers-reduced-motion is honoured live, not sampled once at boot.
 *
 * Public surface: window.DashMotion (plus legacy bridges installed by the
 * host page: dashMotion / ndMotion / dashAnimateContent / dashBump /
 * dashSetText).
 */
(function (global) {
  'use strict';

  if (global.DashMotion && global.DashMotion.version === 5) { return; }

  var doc = global.document;

  /* ======================================================================
   * 0. Environment
   * ==================================================================== */

  var reduceQuery = global.matchMedia ? global.matchMedia('(prefers-reduced-motion: reduce)') : null;
  var prefersReduce = !!(reduceQuery && reduceQuery.matches);
  var userDisabled = false;
  try {
    userDisabled = global.localStorage && global.localStorage.getItem('dash.motion.enabled') === '0';
  } catch (e) { /* storage blocked */ }

  function onReduceChange(ev) {
    prefersReduce = !!ev.matches;
    if (prefersReduce) { Ticker.finishAll(); }
  }
  if (reduceQuery) {
    if (reduceQuery.addEventListener) { reduceQuery.addEventListener('change', onReduceChange); }
    else if (reduceQuery.addListener) { reduceQuery.addListener(onReduceChange); }
  }

  function enabled() { return !prefersReduce && !userDisabled; }

  /* ======================================================================
   * 1. Spring solver
   * ----------------------------------------------------------------------
   * Analytic solution of  m*x'' + c*x' + k*x = 0  expressed as
   * (response, dampingFraction) exactly like SwiftUI's `.spring(response:
   * dampingFraction:)` / Core Animation's CASpringAnimation.
   *
   *   response        = the natural period in seconds (2*pi / omega0).
   *                     Smaller = quicker. This is the *perceptual* duration.
   *   dampingFraction = 1.0 critically damped (no overshoot, the iOS default
   *                     feel), < 1 bouncy, > 1 sluggish.
   *
   * Solving analytically (rather than integrating) means a single exp/cos per
   * channel per frame, exact at any frame rate, and — crucially — trivially
   * resumable from an arbitrary (position, velocity) pair, which is what
   * interruption-with-momentum needs.
   * ==================================================================== */

  function Spring(response, damping) {
    this.response = Math.max(0.0001, response);
    this.damping = Math.max(0, damping);
    this.omega0 = (2 * Math.PI) / this.response;
  }

  /**
   * Advance a channel by dt seconds.
   * @param {number} value    current position
   * @param {number} velocity current velocity (units/second)
   * @param {number} target   where we are heading
   * @param {number} dt       seconds
   * @returns {{value:number, velocity:number}}
   */
  Spring.prototype.step = function (value, velocity, target, dt) {
    var w0 = this.omega0;
    var z = this.damping;
    var d0 = value - target;   // displacement from equilibrium
    var v0 = velocity;
    var d, v, e;

    if (z < 1) {
      // Underdamped — oscillates toward rest.
      var wd = w0 * Math.sqrt(1 - z * z);
      e = Math.exp(-z * w0 * dt);
      var c1 = d0;
      var c2 = (v0 + z * w0 * d0) / wd;
      var cos = Math.cos(wd * dt);
      var sin = Math.sin(wd * dt);
      d = e * (c1 * cos + c2 * sin);
      v = e * ((c2 * wd - z * w0 * c1) * cos - (c1 * wd + z * w0 * c2) * sin);
    } else if (z === 1) {
      // Critically damped — fastest approach with zero overshoot.
      e = Math.exp(-w0 * dt);
      var k1 = d0;
      var k2 = v0 + w0 * d0;
      d = (k1 + k2 * dt) * e;
      v = (k2 - w0 * (k1 + k2 * dt)) * e;
    } else {
      // Overdamped — two real exponentials.
      var r = w0 * Math.sqrt(z * z - 1);
      var r1 = -w0 * z + r;
      var r2 = -w0 * z - r;
      var b2 = (v0 - r1 * d0) / (r2 - r1);
      var b1 = d0 - b2;
      var e1 = Math.exp(r1 * dt);
      var e2 = Math.exp(r2 * dt);
      d = b1 * e1 + b2 * e2;
      v = b1 * r1 * e1 + b2 * r2 * e2;
    }

    return { value: target + d, velocity: v };
  };

  /* ----------------------------------------------------------------------
   * Named springs. These are the whole vocabulary — page code picks a name,
   * never a duration, so the feel stays consistent everywhere.
   * -------------------------------------------------------------------- */
  var SPRINGS = {
    /** Button/press feedback. Very short, fully damped. */
    press:   new Spring(0.22, 1.0),
    /** Default for small UI state changes (hover, chevrons, toggles). */
    snappy:  new Spring(0.32, 1.0),
    /** The house default: content entering, panels, page transitions. */
    smooth:  new Spring(0.44, 1.0),
    /** Large surfaces, sheets, modals — a touch more travel time. */
    gentle:  new Spring(0.58, 1.0),
    /** Deliberate personality: toasts, success states. Mild overshoot only. */
    bouncy:  new Spring(0.42, 0.72),
    /** Popovers and menus — quick but with a whisper of life. */
    pop:     new Spring(0.30, 0.86)
  };

  /* ======================================================================
   * 2. Channel model
   * ----------------------------------------------------------------------
   * Each animatable element owns one record. Transform is stored as separate
   * numeric channels and serialised once per frame, so two concurrent
   * animations (say a hover lift and an entrance) compose instead of
   * clobbering each other's transform string — the classic failure mode of
   * the old GSAP layer.
   * ==================================================================== */

  var CHANNELS = {
    x:       { unit: 'px',  rest: 0.05,   initial: 0 },
    y:       { unit: 'px',  rest: 0.05,   initial: 0 },
    scale:   { unit: '',    rest: 0.0006, initial: 1 },
    rotate:  { unit: 'deg', rest: 0.06,   initial: 0 },
    opacity: { unit: '',    rest: 0.004,  initial: 1 }
  };
  var CHANNEL_NAMES = ['x', 'y', 'scale', 'rotate', 'opacity'];

  var records = typeof WeakMap === 'function' ? new WeakMap() : null;
  var liveRecords = [];

  function recordFor(el, create) {
    if (!el || !el.nodeType) { return null; }
    var rec = records ? records.get(el) : el.__dashMotionRecord;
    if (rec || !create) { return rec || null; }
    rec = {
      el: el,
      live: false,
      dirty: false,
      channels: {},
      onRest: null
    };
    for (var i = 0; i < CHANNEL_NAMES.length; i++) {
      var name = CHANNEL_NAMES[i];
      rec.channels[name] = {
        value: CHANNELS[name].initial,
        velocity: 0,
        target: CHANNELS[name].initial,
        spring: null,
        delay: 0,
        moving: false
      };
    }
    if (records) { records.set(el, rec); } else { el.__dashMotionRecord = rec; }
    return rec;
  }

  function activate(rec) {
    if (rec.live) { return; }
    rec.live = true;
    liveRecords.push(rec);
    Ticker.start();
  }

  function anyMoving(rec) {
    for (var i = 0; i < CHANNEL_NAMES.length; i++) {
      if (rec.channels[CHANNEL_NAMES[i]].moving) { return true; }
    }
    return false;
  }

  function commit(rec) {
    var c = rec.channels;
    var style = rec.el.style;
    var tx = c.x.value, ty = c.y.value, s = c.scale.value, r = c.rotate.value;

    if (tx === 0 && ty === 0 && s === 1 && r === 0) {
      // Park at identity rather than leaving a transform behind — a lingering
      // `transform: translate3d(0,0,0)` promotes a layer forever and changes
      // stacking/blur rendering on some engines.
      if (style.transform) { style.transform = ''; }
    } else {
      var t = 'translate3d(' + tx.toFixed(3) + 'px,' + ty.toFixed(3) + 'px,0)';
      if (r !== 0) { t += ' rotate(' + r.toFixed(3) + 'deg)'; }
      if (s !== 1) { t += ' scale(' + s.toFixed(5) + ')'; }
      style.transform = t;
    }

    if (c.opacity.value >= 0.999) {
      if (style.opacity) { style.opacity = ''; }
    } else {
      style.opacity = c.opacity.value.toFixed(4);
    }
  }

  /* ======================================================================
   * 3. Ticker — a single rAF loop for the whole page
   * ==================================================================== */

  var Ticker = {
    running: false,
    last: 0,
    frame: 0,

    start: function () {
      if (this.running) { return; }
      this.running = true;
      this.last = 0;
      this.frame = global.requestAnimationFrame(Ticker.tick);
    },

    stop: function () {
      this.running = false;
      if (this.frame) { global.cancelAnimationFrame(this.frame); this.frame = 0; }
    },

    tick: function (now) {
      if (!Ticker.running) { return; }
      var dt;
      if (!Ticker.last) { dt = 1 / 60; } else { dt = (now - Ticker.last) / 1000; }
      Ticker.last = now;
      // Clamp: a backgrounded tab or a GC pause must not teleport springs.
      if (dt > 0.064) { dt = 0.064; }
      if (dt <= 0) { dt = 1 / 60; }

      var i, j, rec, ch, name, res, def, settled;
      var writes = [];

      // --- integrate (pure maths, no DOM touch) ---
      for (i = 0; i < liveRecords.length; i++) {
        rec = liveRecords[i];
        settled = true;
        for (j = 0; j < CHANNEL_NAMES.length; j++) {
          name = CHANNEL_NAMES[j];
          ch = rec.channels[name];
          if (!ch.moving) { continue; }

          if (ch.delay > 0) {
            ch.delay -= dt;
            settled = false;
            continue;
          }

          def = CHANNELS[name];
          res = (ch.spring || SPRINGS.smooth).step(ch.value, ch.velocity, ch.target, dt);
          ch.value = res.value;
          ch.velocity = res.velocity;

          if (Math.abs(ch.value - ch.target) < def.rest && Math.abs(ch.velocity) < def.rest * 12) {
            ch.value = ch.target;
            ch.velocity = 0;
            ch.moving = false;
          } else {
            settled = false;
          }
        }
        rec.settledThisFrame = settled;
        writes.push(rec);
      }

      // --- write (all DOM mutation in one pass) ---
      for (i = 0; i < writes.length; i++) { commit(writes[i]); }

      // --- reap ---
      for (i = liveRecords.length - 1; i >= 0; i--) {
        rec = liveRecords[i];
        if (!anyMoving(rec)) {
          rec.live = false;
          liveRecords.splice(i, 1);
          if (rec.el.style.willChange) { rec.el.style.willChange = ''; }
          if (rec.onRest) { var cb = rec.onRest; rec.onRest = null; cb(); }
        }
      }

      if (liveRecords.length) {
        Ticker.frame = global.requestAnimationFrame(Ticker.tick);
      } else {
        Ticker.running = false;
        Ticker.frame = 0;
      }
    },

    /** Jump every in-flight animation to its resting state immediately. */
    finishAll: function () {
      for (var i = liveRecords.length - 1; i >= 0; i--) {
        var rec = liveRecords[i];
        for (var j = 0; j < CHANNEL_NAMES.length; j++) {
          var ch = rec.channels[CHANNEL_NAMES[j]];
          ch.value = ch.target; ch.velocity = 0; ch.moving = false; ch.delay = 0;
        }
        commit(rec);
        rec.live = false;
        rec.el.style.willChange = '';
        if (rec.onRest) { var cb = rec.onRest; rec.onRest = null; cb(); }
      }
      liveRecords.length = 0;
      Ticker.stop();
    }
  };

  /* ======================================================================
   * 4. Core API
   * ==================================================================== */

  function resolveSpring(opt) {
    if (!opt) { return SPRINGS.smooth; }
    if (opt instanceof Spring) { return opt; }
    if (typeof opt === 'string') { return SPRINGS[opt] || SPRINGS.smooth; }
    if (typeof opt === 'object' && opt.response) {
      return new Spring(opt.response, opt.damping == null ? 1 : opt.damping);
    }
    return SPRINGS.smooth;
  }

  /** Set channels instantly, without animating. */
  function set(el, props) {
    var rec = recordFor(el, true);
    if (!rec) { return; }
    for (var name in props) {
      if (!rec.channels[name]) { continue; }
      var ch = rec.channels[name];
      ch.value = props[name];
      ch.target = props[name];
      ch.velocity = 0;
      ch.moving = false;
    }
    commit(rec);
  }

  /**
   * Spring `el` toward `props`.
   *
   * Retargeting an already-moving channel keeps its current position AND
   * velocity, so an interrupted animation flows into the new one instead of
   * restarting. That continuity is what the old engine's `overwrite:'auto'`
   * destroyed on every single interaction.
   */
  function animate(el, props, opts) {
    var rec = recordFor(el, true);
    if (!rec) { return null; }
    opts = opts || {};

    if (!enabled()) {
      set(el, props);
      if (opts.onRest) { opts.onRest(); }
      return rec;
    }

    var spring = resolveSpring(opts.spring);
    var delay = opts.delay || 0;
    var touched = false;

    for (var name in props) {
      var ch = rec.channels[name];
      if (!ch) { continue; }
      var target = props[name];
      if (ch.target === target && !ch.moving) { continue; }
      ch.target = target;
      ch.spring = spring;
      ch.delay = delay;
      ch.moving = true;
      touched = true;
    }

    if (!touched) {
      if (opts.onRest) { opts.onRest(); }
      return rec;
    }

    rec.onRest = opts.onRest || null;
    if (opts.hint !== false) { rec.el.style.willChange = 'transform, opacity'; }
    activate(rec);
    return rec;
  }

  /** Convenience: start from `from`, spring to `to`. */
  function animateFrom(el, from, to, opts) {
    set(el, from);
    return animate(el, to, opts);
  }

  /** Stop an element where it stands, keeping its current rendered values. */
  function stop(el) {
    var rec = recordFor(el, false);
    if (!rec) { return; }
    for (var i = 0; i < CHANNEL_NAMES.length; i++) {
      var ch = rec.channels[CHANNEL_NAMES[i]];
      ch.target = ch.value;
      ch.velocity = 0;
      ch.moving = false;
      ch.delay = 0;
    }
    rec.onRest = null;
  }

  /** Release the element back to CSS control (clears inline transform/opacity). */
  function release(el) {
    stop(el);
    var rec = recordFor(el, false);
    if (!rec) { return; }
    for (var i = 0; i < CHANNEL_NAMES.length; i++) {
      var name = CHANNEL_NAMES[i];
      rec.channels[name].value = CHANNELS[name].initial;
      rec.channels[name].target = CHANNELS[name].initial;
    }
    rec.el.style.transform = '';
    rec.el.style.opacity = '';
    rec.el.style.willChange = '';
  }

  /* ======================================================================
   * 5. Scalar springs (numbers that aren't CSS properties)
   * ==================================================================== */

  function tweenNumber(from, to, opts) {
    opts = opts || {};
    var spring = resolveSpring(opts.spring || 'smooth');
    var value = from, velocity = 0, stopped = false;
    if (!enabled()) {
      if (opts.onUpdate) { opts.onUpdate(to); }
      if (opts.onRest) { opts.onRest(); }
      return function () {};
    }
    var last = 0;
    function frame(now) {
      if (stopped) { return; }
      var dt = last ? (now - last) / 1000 : 1 / 60;
      last = now;
      if (dt > 0.064) { dt = 0.064; }
      var res = spring.step(value, velocity, to, dt);
      value = res.value; velocity = res.velocity;
      if (Math.abs(value - to) < (opts.rest || 0.5) && Math.abs(velocity) < (opts.rest || 0.5) * 12) {
        value = to;
        if (opts.onUpdate) { opts.onUpdate(value); }
        if (opts.onRest) { opts.onRest(); }
        return;
      }
      if (opts.onUpdate) { opts.onUpdate(value); }
      global.requestAnimationFrame(frame);
    }
    global.requestAnimationFrame(frame);
    return function () { stopped = true; };
  }

  /* ======================================================================
   * 6. Helpers
   * ==================================================================== */

  function list(root, selector) {
    if (!root || !root.querySelectorAll) { return []; }
    return Array.prototype.slice.call(root.querySelectorAll(selector));
  }

  function unique(items) {
    var out = [], seen = [];
    for (var i = 0; i < items.length; i++) {
      if (items[i] && seen.indexOf(items[i]) < 0) { seen.push(items[i]); out.push(items[i]); }
    }
    return out;
  }

  var viewportH = function () { return global.innerHeight || doc.documentElement.clientHeight || 800; };

  /** Is the element both laid out and within (roughly) the viewport? */
  function onScreen(el, slack) {
    if (!el || !el.getBoundingClientRect) { return false; }
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) { return false; }
    var pad = slack == null ? 120 : slack;
    return r.bottom > -pad && r.top < viewportH() + pad;
  }

  function closest(el, selector) {
    return el && el.closest ? el.closest(selector) : null;
  }

  /* Prefix-agnostic selectors: the four repos ship `dash-*` (Dash/Fabric/
   * Forge) and `nd-*` (NeoDash) class names for the same components, so the
   * engine matches both and one file serves every build. */
  function sel() {
    var parts = [];
    for (var i = 0; i < arguments.length; i++) {
      var base = arguments[i];
      parts.push('.dash-' + base, '.nd-' + base);
    }
    return parts.join(',');
  }

  var SEL_CARD = sel('metric-card', 'product-card', 'panel', 'hover-lift') + ',article[data-server-id]';
  var SEL_ROW = 'tbody tr,[data-offline-file-row],' + sel('alert-row');
  var SEL_PRESSABLE = 'button:not([disabled]),[role="button"],' + sel('sidebar') + ' a[href],#sidebar a[href],' + SEL_CARD;

  /* ======================================================================
   * 7. Entrance choreography
   * ----------------------------------------------------------------------
   * The old engine animated up to 70 cards + 160 rows individually on every
   * navigation. That is ~230 concurrent tweens each writing inline styles —
   * the dominant cost, and the reason navigation stuttered.
   *
   * The new approach: always animate the container, then add a *capped*,
   * *on-screen-only* stagger for the first row of children. Everything below
   * the fold arrives already at rest.
   * ==================================================================== */

  var MAX_STAGGER_ITEMS = 14;
  var STAGGER_STEP = 0.028;   // seconds between siblings
  var STAGGER_TOTAL = 0.22;   // hard cap on the whole cascade

  function mainOf(root) {
    var r = root || doc.getElementById('dash-content') || doc.getElementById('main-content') || doc;
    if (!r.querySelector) { return null; }
    return r.querySelector('main') || r;
  }

  function staggerDelays(count) {
    if (count <= 1) { return [0]; }
    var step = Math.min(STAGGER_STEP, STAGGER_TOTAL / (count - 1));
    var out = [];
    for (var i = 0; i < count; i++) { out.push(i * step); }
    return out;
  }

  /**
   * Reveal freshly-mounted content.
   * @param {Element} root
   * @param {{subtle?:boolean, onRest?:Function}} opts
   */
  function reveal(root, opts) {
    opts = opts || {};
    var container = mainOf(root);
    if (!container) { return false; }

    if (!enabled()) {
      release(container);
      if (opts.onRest) { opts.onRest(); }
      return false;
    }

    var subtle = !!opts.subtle;

    // 1. The container itself carries the bulk of the motion.
    animateFrom(container,
      { opacity: subtle ? 0.82 : 0, y: subtle ? 4 : 12 },
      { opacity: 1, y: 0 },
      { spring: subtle ? 'snappy' : 'smooth', onRest: function () {
          release(container);
          if (opts.onRest) { opts.onRest(); }
        } });

    // 2. A short, capped cascade over whatever is actually visible.
    var candidates = unique(
      Array.prototype.slice.call(container.children)
        .filter(function (n) { return n.nodeType === 1 && n.tagName !== 'SCRIPT' && n.tagName !== 'STYLE'; })
        .concat(list(container, SEL_CARD))
    ).filter(function (el) { return onScreen(el, 40); }).slice(0, MAX_STAGGER_ITEMS);

    var delays = staggerDelays(candidates.length);
    for (var i = 0; i < candidates.length; i++) {
      (function (el, delay) {
        animateFrom(el,
          { opacity: subtle ? 0.6 : 0, y: subtle ? 5 : 10 },
          { opacity: 1, y: 0 },
          { spring: 'smooth', delay: delay, onRest: function () { release(el); } });
      }(candidates[i], delays[i]));
    }

    return true;
  }

  /** Exit half of a page transition. Deliberately tiny — the enter carries it. */
  function exit(root, onRest) {
    var container = mainOf(root);
    if (!container || !enabled()) { if (onRest) { onRest(); } return false; }
    animate(container, { opacity: 0.55, y: -6 }, {
      spring: 'press',
      onRest: onRest || null
    });
    return true;
  }

  /** Swap content in place with a subtle settle. */
  function swap(apply, done) {
    if (typeof apply !== 'function') { return false; }
    var content = doc.getElementById('dash-content') || doc.getElementById('main-content') || doc.body;
    apply();
    if (!enabled()) { if (done) { done(); } return false; }
    reveal(content, { subtle: true, onRest: done });
    return true;
  }

  /* ----------------------------------------------------------------------
   * Scroll reveal — content below the fold springs in as it scrolls into
   * view. Previously nonexistent; cheap because IntersectionObserver does
   * the work off the main thread.
   * -------------------------------------------------------------------- */

  var scrollObserver = null;

  function ensureScrollObserver() {
    if (scrollObserver || !global.IntersectionObserver) { return scrollObserver; }
    scrollObserver = new global.IntersectionObserver(function (entries) {
      for (var i = 0; i < entries.length; i++) {
        var entry = entries[i];
        if (!entry.isIntersecting) { continue; }
        var el = entry.target;
        scrollObserver.unobserve(el);
        el.removeAttribute('data-motion-pending');
        if (!enabled()) { release(el); continue; }
        animateFrom(el, { opacity: 0, y: 14 }, { opacity: 1, y: 0 },
          { spring: 'smooth', onRest: function () { release(el); } });
      }
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.01 });
    return scrollObserver;
  }

  function observeBelowFold(root) {
    var io = ensureScrollObserver();
    if (!io || !enabled()) { return; }
    var items = list(root || doc, SEL_CARD);
    for (var i = 0; i < items.length; i++) {
      var el = items[i];
      if (el.getAttribute('data-motion-pending') === '1') { continue; }
      if (onScreen(el, 0)) { continue; }
      el.setAttribute('data-motion-pending', '1');
      set(el, { opacity: 0, y: 14 });
      io.observe(el);
    }
  }

  /* ======================================================================
   * 8. FLIP — position changes that animate instead of jumping
   * ==================================================================== */

  /**
   * @param {Element[]} elements elements whose layout `mutate` will change
   * @param {Function}  mutate   synchronous DOM mutation
   */
  function flip(elements, mutate, opts) {
    opts = opts || {};
    if (!enabled() || !elements || !elements.length) { mutate(); return; }

    var first = [], i, el, r;
    for (i = 0; i < elements.length; i++) {
      first.push(elements[i].getBoundingClientRect());
    }

    mutate();

    for (i = 0; i < elements.length; i++) {
      el = elements[i];
      r = el.getBoundingClientRect();
      var dx = first[i].left - r.left;
      var dy = first[i].top - r.top;
      if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) { continue; }
      // Invert, then play — carrying any in-flight velocity along.
      var rec = recordFor(el, true);
      rec.channels.x.value += dx;
      rec.channels.y.value += dy;
      commit(rec);
      (function (node) {
        animate(node, { x: 0, y: 0 }, {
          spring: opts.spring || 'smooth',
          onRest: function () { release(node); }
        });
      }(el));
    }
  }

  /* ======================================================================
   * 9. Interaction layer
   * ----------------------------------------------------------------------
   * Delegated, passive listeners. The engine owns transform on these
   * elements; the CSS file deliberately does NOT transition transform on
   * anything, so there is exactly one animator per property.
   * ==================================================================== */

  var pressed = null;

  function onPointerDown(ev) {
    if (!enabled()) { return; }
    var el = closest(ev.target, SEL_PRESSABLE);
    if (!el) { return; }
    pressed = el;
    animate(el, { scale: 0.972 }, { spring: 'press' });
  }

  function releasePress() {
    if (!pressed) { return; }
    var el = pressed;
    pressed = null;
    // Springs back with the velocity it already had — no snap, no elastic ring.
    animate(el, { scale: 1 }, { spring: 'bouncy', onRest: function () {
      var rec = recordFor(el, false);
      if (rec && !anyMoving(rec)) { release(el); }
    } });
  }

  function onPointerEnter(ev) {
    if (!enabled()) { return; }
    var el = closest(ev.target, SEL_CARD);
    if (!el || (ev.relatedTarget && el.contains(ev.relatedTarget))) { return; }
    if (el === pressed) { return; }
    animate(el, { y: -2, scale: 1.004 }, { spring: 'snappy' });
  }

  function onPointerLeave(ev) {
    if (!enabled()) { return; }
    var el = closest(ev.target, SEL_CARD);
    if (!el || (ev.relatedTarget && el.contains(ev.relatedTarget))) { return; }
    animate(el, { y: 0, scale: 1 }, { spring: 'snappy', onRest: function () { release(el); } });
  }

  /* Pointer-tracked spotlight. Throttled to one write per frame — the old
   * version wrote two custom properties on every pointermove event, which on
   * a 1000 Hz mouse meant ~16 style invalidations per frame. */
  var spotTarget = null, spotX = 0, spotY = 0, spotQueued = false;

  function flushSpot() {
    spotQueued = false;
    if (!spotTarget) { return; }
    var r = spotTarget.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) { return; }
    var px = ((spotX - r.left) / r.width * 100).toFixed(1) + '%';
    var py = ((spotY - r.top) / r.height * 100).toFixed(1) + '%';
    spotTarget.style.setProperty('--dash-spot-x', px);
    spotTarget.style.setProperty('--dash-spot-y', py);
    spotTarget.style.setProperty('--nd-spot-x', px);
    spotTarget.style.setProperty('--nd-spot-y', py);
  }

  function onPointerMove(ev) {
    if (!enabled()) { return; }
    var card = closest(ev.target, sel('metric-card', 'product-card'));
    if (!card) { spotTarget = null; return; }
    spotTarget = card; spotX = ev.clientX; spotY = ev.clientY;
    if (!spotQueued) { spotQueued = true; global.requestAnimationFrame(flushSpot); }
  }

  function installInteractions() {
    var passive = { passive: true, capture: true };
    doc.addEventListener('pointerdown', onPointerDown, passive);
    doc.addEventListener('pointerup', releasePress, passive);
    doc.addEventListener('pointercancel', releasePress, passive);
    doc.addEventListener('pointerover', onPointerEnter, passive);
    doc.addEventListener('pointerout', onPointerLeave, passive);
    doc.addEventListener('pointermove', onPointerMove, passive);
    global.addEventListener('blur', releasePress, true);
  }

  /* ======================================================================
   * 10. Feedback primitives
   * ==================================================================== */

  var progressTimer = null;

  function progress(on) {
    var bar = doc.getElementById('dash-progress-bar');
    var layer = doc.getElementById('dash-loading-layer');
    if (on) {
      if (bar) { bar.classList.add('is-active'); }
      if (layer) {
        layer.setAttribute('aria-hidden', 'false');
        clearTimeout(progressTimer);
        // Only surface the overlay if the work is actually slow; anything
        // faster than this just flashes and reads as jank.
        progressTimer = setTimeout(function () { layer.classList.add('is-active'); }, 260);
      }
    } else {
      if (bar) { bar.classList.remove('is-active'); }
      clearTimeout(progressTimer);
      if (layer) { layer.classList.remove('is-active'); layer.setAttribute('aria-hidden', 'true'); }
    }
  }

  function busy(btn, on, restoreHtml) {
    if (!btn) { return; }
    if (on) {
      if (btn.dataset.dashBusy === '1') { return; }
      btn.dataset.dashBusy = '1';
      btn.dataset.dashBusyHtml = restoreHtml || btn.innerHTML || '';
      btn.disabled = true;
      btn.setAttribute('aria-busy', 'true');
      btn.innerHTML = '<span class="dash-submit-loader nd-submit-loader" aria-hidden="true"></span><span class="sr-only">Loading</span>';
      animateFrom(btn, { scale: 0.985 }, { scale: 1 }, { spring: 'bouncy' });
      return;
    }
    var html = restoreHtml || btn.dataset.dashBusyHtml;
    btn.disabled = false;
    btn.removeAttribute('aria-busy');
    if (html) { btn.innerHTML = html; }
    delete btn.dataset.dashBusy;
    delete btn.dataset.dashBusyHtml;
  }

  /** Attention pulse on a value that just changed. Scale only — no text-shadow. */
  function bump(el) {
    if (!el || !enabled()) { return; }
    var rec = recordFor(el, true);
    // Kick it with velocity instead of jumping to a "from" value: the element
    // never teleports, it just gets pushed.
    rec.channels.scale.value = 1.045;
    rec.channels.y.value = -1.5;
    commit(rec);
    animate(el, { scale: 1, y: 0 }, { spring: 'bouncy', onRest: function () { release(el); } });
  }

  /** Card acknowledgement when its data refreshed. */
  function pulseCard(el) {
    var card = closest(el, SEL_CARD);
    if (!card || !enabled()) { return; }
    card.classList.add('is-refreshing');
    setTimeout(function () { card.classList.remove('is-refreshing'); }, 720);
  }

  /**
   * Set text, counting numbers up instead of snapping when the shape allows.
   */
  function setText(el, value) {
    if (!el) { return; }
    var next = String(value);
    var current = (el.textContent || '').trim();
    if (current === next.trim()) { return; }

    var a = current.match(/^(-?[\d.,]+)(.*)$/);
    var b = next.trim().match(/^(-?[\d.,]+)(.*)$/);
    var tweened = false;

    if (enabled() && a && b && a[2] === b[2]) {
      var fromNum = parseFloat(a[1].replace(/,/g, ''));
      var toNum = parseFloat(b[1].replace(/,/g, ''));
      var decimals = (b[1].split('.')[1] || '').length;
      if (isFinite(fromNum) && isFinite(toNum) && Math.abs(toNum - fromNum) <= 1e7) {
        var suffix = b[2];
        if (el.__dashCountStop) { el.__dashCountStop(); }
        el.__dashCountStop = tweenNumber(fromNum, toNum, {
          spring: 'gentle',
          rest: decimals ? Math.pow(10, -decimals) / 2 : 0.5,
          onUpdate: function (v) { el.textContent = v.toFixed(decimals) + suffix; },
          onRest: function () { el.textContent = next; el.__dashCountStop = null; }
        });
        tweened = true;
      }
    }

    if (!tweened) { el.textContent = next; }
    bump(el);
    pulseCard(el);
  }

  /** Release inline styles as soon as a popover's `data-open` flag clears. */
  function watchOpenState(menu) {
    if (!global.MutationObserver || menu.__dashOpenWatch) { return; }
    var observer = new global.MutationObserver(function () {
      if (menu.getAttribute('data-open') === 'true') { return; }
      stop(menu);
      release(menu);
      var options = list(menu, sel('select-option'));
      for (var i = 0; i < options.length; i++) { stop(options[i]); release(options[i]); }
    });
    observer.observe(menu, { attributes: true, attributeFilter: ['data-open'] });
    menu.__dashOpenWatch = observer;
  }

  /**
   * Popover / menu entrance.
   *
   * The host toggles menu visibility through a `data-open` attribute that CSS
   * reads. Because the engine writes inline opacity/transform, a menu closed
   * mid-animation would keep those inline values and stay stuck on screen, so
   * we watch the attribute and hand the element straight back to CSS the
   * moment it closes.
   */
  function popIn(menu) {
    if (!menu || !enabled()) { return; }
    watchOpenState(menu);
    var fromY = menu.dataset && menu.dataset.placement === 'top' ? 8 : -8;
    animateFrom(menu, { opacity: 0, y: fromY, scale: 0.97 }, { opacity: 1, y: 0, scale: 1 },
      { spring: 'pop', onRest: function () { release(menu); } });

    var options = list(menu, sel('select-option')).slice(0, 10);
    var delays = staggerDelays(options.length);
    for (var i = 0; i < options.length; i++) {
      (function (opt, delay) {
        animateFrom(opt, { opacity: 0, y: 4 }, { opacity: 1, y: 0 },
          { spring: 'snappy', delay: delay, onRest: function () { release(opt); } });
      }(options[i], delays[i]));
    }
  }

  function popOut(menu, onRest) {
    if (!menu || !enabled()) { if (onRest) { onRest(); } return; }
    animate(menu, { opacity: 0, y: -6, scale: 0.98 }, { spring: 'press', onRest: onRest || null });
  }

  /** Toast entrance/exit. Slides from the edge; no confetti, no shake. */
  function toastIn(el) {
    if (!el || !enabled()) { return; }
    animateFrom(el, { opacity: 0, x: 24, scale: 0.97 }, { opacity: 1, x: 0, scale: 1 },
      { spring: 'bouncy', onRest: function () { release(el); } });
  }

  function toastOut(el, onRest) {
    if (!el) { if (onRest) { onRest(); } return; }
    if (!enabled()) { if (onRest) { onRest(); } return; }
    animate(el, { opacity: 0, x: 20, scale: 0.98 }, { spring: 'press', onRest: onRest || null });
  }

  /* ======================================================================
   * 11. Incremental content (polling refreshes)
   * ----------------------------------------------------------------------
   * The panels poll every 2s and swap DOM. The old MutationObserver
   * re-animated every added row on every poll, so the page was never still.
   * Here: only animate additions that are on screen, cap them hard, and only
   * when the mutation burst is small (a big burst is a page swap, which the
   * reveal path already handles).
   * ==================================================================== */

  function installMutationReveal() {
    if (!global.MutationObserver) { return; }
    var root = doc.getElementById('dash-content') || doc.getElementById('main-content');
    if (!root) { return; }

    new global.MutationObserver(function (recs) {
      if (!enabled()) { return; }
      var added = [];
      for (var i = 0; i < recs.length; i++) {
        var nodes = recs[i].addedNodes || [];
        for (var j = 0; j < nodes.length; j++) {
          var n = nodes[j];
          if (n.nodeType !== 1) { continue; }
          if (n.matches && n.matches(SEL_ROW)) { added.push(n); }
        }
      }
      if (!added.length || added.length > 24) { return; }
      added = added.filter(function (el) { return onScreen(el, 0); }).slice(0, 12);
      var delays = staggerDelays(added.length);
      for (var k = 0; k < added.length; k++) {
        (function (el, delay) {
          animateFrom(el, { opacity: 0, x: 6 }, { opacity: 1, x: 0 },
            { spring: 'snappy', delay: delay, onRest: function () { release(el); } });
        }(added[k], delays[k]));
      }
    }).observe(root, { childList: true, subtree: true });
  }

  /* ======================================================================
   * 12. Boot
   * ==================================================================== */

  function boot() {
    installInteractions();
    installMutationReveal();
    observeBelowFold(doc);
  }

  if (doc.readyState === 'loading') {
    doc.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }

  /* ======================================================================
   * 13. Public surface
   * ==================================================================== */

  var DashMotion = {
    version: 5,
    Spring: Spring,
    springs: SPRINGS,

    // core
    animate: animate,
    animateFrom: animateFrom,
    set: set,
    stop: stop,
    release: release,
    tweenNumber: tweenNumber,
    flip: flip,

    // choreography
    reveal: reveal,
    enter: reveal,
    exit: exit,
    swap: swap,
    observeBelowFold: observeBelowFold,

    // feedback
    progress: progress,
    busy: busy,
    bump: bump,
    setText: setText,
    pulseCard: pulseCard,
    pulseMetric: pulseCard,
    popIn: popIn,
    popOut: popOut,
    pop: popIn,
    toastIn: toastIn,
    toastOut: toastOut,

    // state
    enabled: enabled,
    active: enabled,
    setEnabled: function (on) {
      userDisabled = !on;
      try { global.localStorage.setItem('dash.motion.enabled', on ? '1' : '0'); } catch (e) {}
      if (!on) { Ticker.finishAll(); }
    },
    finishAll: function () { Ticker.finishAll(); }
  };

  global.DashMotion = DashMotion;

  /* ----------------------------------------------------------------------
   * Legacy bridges. Every page in all four repos calls these names; keeping
   * them means zero page-level edits are required.
   * -------------------------------------------------------------------- */
  global.dashMotion = DashMotion;
  global.ndMotion = DashMotion;

  global.dashAnimateContent = function (root) { reveal(root); observeBelowFold(root || doc); };
  global.ndAnimateContent = global.dashAnimateContent;

  global.dashBump = function (el) { bump(el); };
  global.ndBump = global.dashBump;

  global.dashSetText = function (el, value) { setText(el, value); };
  global.ndSetText = global.dashSetText;

}(window));
