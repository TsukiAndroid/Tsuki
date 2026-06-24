/**
 * element_picker.js — Injected by ElementPickerWebView into every page.
 *
 * Enables point-and-click CSS selector capture for the Visual Rule Builder.
 * Communicates back to Kotlin via the Android JS interface "TsukiPicker".
 */
(function () {
    'use strict';

    if (window.__tsukiPickerActive) return;
    window.__tsukiPickerActive = true;

    /* ── CSS injection ──────────────────────────────────────────────────────── */
    var style = document.createElement('style');
    style.textContent = [
        '.tsuki-highlight-hover{outline:2px solid #9d7fff!important;cursor:pointer!important;box-shadow:0 0 0 2px rgba(157,127,255,.25)!important;}',
        '.tsuki-highlight-selected{outline:3px solid #7c5cff!important;background:rgba(124,92,255,.10)!important;}',
        '.tsuki-highlight-sibling{outline:2px dashed #c8b8ff!important;background:rgba(200,184,255,.05)!important;}',
    ].join('');
    document.head.appendChild(style);

    document.body.classList.add('tsuki-picker-active');

    /* ── State ──────────────────────────────────────────────────────────────── */
    var hoveredEl = null;
    var selectedEl = null;

    /* ── Utilities ──────────────────────────────────────────────────────────── */

    function safeText(el) {
        if (!el) return '';
        return (el.innerText || el.textContent || '').trim().substring(0, 200);
    }

    function safeHtml(el) {
        if (!el) return '';
        return (el.outerHTML || '').substring(0, 2000);
    }

    /** Remove all picker highlight classes from the entire document. */
    function clearHighlights() {
        var els = document.querySelectorAll('.tsuki-highlight-selected,.tsuki-highlight-sibling,.tsuki-highlight-hover');
        for (var i = 0; i < els.length; i++) {
            els[i].classList.remove('tsuki-highlight-selected', 'tsuki-highlight-sibling', 'tsuki-highlight-hover');
        }
    }

    /* ── Selector generation ────────────────────────────────────────────────── */

    var SKIP_TAGS = ['HTML', 'BODY', 'HEAD', 'SCRIPT', 'STYLE', 'NOSCRIPT'];
    var NAV_TAGS  = ['NAV', 'HEADER', 'FOOTER'];

    /** Returns true for class names that look auto-generated / unstable. */
    function isStableClass(cls) {
        if (!cls || cls.length < 2) return false;
        // Skip random hash-like classes (tailwind JIT IDs, CSS-modules, etc.)
        if (/^[a-z0-9]{6,}$/i.test(cls) && !/[_-]/.test(cls)) return false;
        // Skip numeric-only
        if (/^\d+$/.test(cls)) return false;
        return true;
    }

    /** Pick the most stable class selector fragment from an element's classList. */
    function bestClassSelector(el) {
        if (!el.classList || el.classList.length === 0) return null;
        var stable = [];
        for (var i = 0; i < el.classList.length; i++) {
            var c = el.classList[i];
            if (c && isStableClass(c) && c !== 'tsuki-highlight-hover' &&
                c !== 'tsuki-highlight-selected' && c !== 'tsuki-highlight-sibling' &&
                c !== 'tsuki-picker-active') {
                stable.push(c);
            }
        }
        if (stable.length === 0) return null;
        // Prefer shorter selectors (less brittle). Keep at most 2 classes.
        stable.sort(function (a, b) { return a.length - b.length; });
        return '.' + stable.slice(0, 2).join('.');
    }

    /** Generate the most stable CSS selector for a given element. */
    function generateSelector(el) {
        if (!el || SKIP_TAGS.indexOf(el.tagName) !== -1) return null;

        var tag = el.tagName.toLowerCase();

        // Strategy A: stable class on the element itself
        var cls = bestClassSelector(el);
        if (cls) {
            var candidate = tag + cls;
            // Prefer shortest selector that matches more than 1 element
            try {
                if (document.querySelectorAll(candidate).length > 0) return candidate;
            } catch (e) {}
            // Fallback: just class without tag
            try {
                if (document.querySelectorAll(cls).length > 0) return cls;
            } catch (e) {}
        }

        // Strategy B: data attribute
        var dataAttr = el.getAttribute('data-type') || el.getAttribute('data-id') || el.getAttribute('data-manga');
        if (dataAttr) {
            var dataSel = '[data-type="' + dataAttr + '"]';
            try {
                if (document.querySelectorAll(dataSel).length > 1) return dataSel;
            } catch (e) {}
        }

        // Strategy C: parent with stable class > this tag
        var parent = el.parentElement;
        if (parent) {
            var parentCls = bestClassSelector(parent);
            if (parentCls) {
                var childSel = parentCls + ' > ' + tag;
                try {
                    if (document.querySelectorAll(childSel).length > 1) return childSel;
                } catch (e) {}
                // Broader: parentCls tag (not direct child)
                var looseChildSel = parentCls + ' ' + tag;
                try {
                    if (document.querySelectorAll(looseChildSel).length > 1) return looseChildSel;
                } catch (e) {}
            }
        }

        // Strategy D: semantic tag + class at grandparent level
        var gp = parent && parent.parentElement;
        if (gp) {
            var gpCls = bestClassSelector(gp);
            if (gpCls) {
                var gpSel = gpCls + ' ' + tag;
                try {
                    if (document.querySelectorAll(gpSel).length > 1) return gpSel;
                } catch (e) {}
            }
        }

        // Strategy E: tag-only (last resort — very broad, user needs to be aware)
        return tag;
    }

    /** Walk up the DOM to find the best card-container ancestor.
     *  Returns the ancestor element or null.
     *  Heuristic: ancestor that contains both an <img> and a text node AND
     *  appears more than 3 times on the page. */
    function findCardContainer(el) {
        var current = el ? el.parentElement : null;
        var attempts = 0;
        while (current && current.tagName !== 'BODY' && attempts < 8) {
            attempts++;
            var hasImg  = current.querySelector('img') !== null;
            var hasText = (current.innerText || '').trim().length > 2;
            if (hasImg && hasText) {
                var sel = generateSelector(current);
                if (sel) {
                    try {
                        var matches = document.querySelectorAll(sel);
                        if (matches.length > 3) {
                            return { element: current, selector: sel, count: matches.length };
                        }
                    } catch (e) {}
                }
            }
            current = current.parentElement;
        }
        return null;
    }

    /** Returns a warning string if the tapped element looks wrong, else null. */
    function checkWrongElement(el) {
        if (!el) return null;
        var tag = el.tagName;
        // Navigation elements
        var isInNav = false;
        var parent = el;
        for (var i = 0; i < 5; i++) {
            if (!parent) break;
            if (NAV_TAGS.indexOf(parent.tagName) !== -1) { isInNav = true; break; }
            parent = parent.parentElement;
        }
        if (isInNav) return 'WARN_NAV';
        // Logo / icon checks
        var src = el.getAttribute('src') || el.getAttribute('data-src') || '';
        if (/logo|favicon|site-icon|brand|header-icon/i.test(src)) return 'WARN_LOGO';
        // Size heuristic: very small images are likely icons/logos
        var w = el.naturalWidth || el.width || parseInt(el.getAttribute('width')) || 0;
        var h = el.naturalHeight || el.height || parseInt(el.getAttribute('height')) || 0;
        if (w > 0 && w < 60 && h > 0 && h < 60) return 'WARN_LOGO';
        // Ad detection
        var id = (el.id || '').toLowerCase();
        var cls = (el.className || '').toLowerCase();
        if (/ad[- _]|advertisement|adsense|banner/i.test(id + ' ' + cls)) return 'WARN_AD';
        return null;
    }

    /* ── Hover effect ───────────────────────────────────────────────────────── */

    function onMouseMove(e) {
        var el = e.target;
        if (!el || SKIP_TAGS.indexOf(el.tagName) !== -1) return;
        if (el === hoveredEl) return;
        if (hoveredEl) hoveredEl.classList.remove('tsuki-highlight-hover');
        hoveredEl = el;
        if (el !== selectedEl) el.classList.add('tsuki-highlight-hover');
    }

    document.addEventListener('mousemove', onMouseMove, true);

    // Touch hover equivalent (touchmove)
    document.addEventListener('touchmove', function (e) {
        var touch = e.touches[0];
        if (!touch) return;
        var el = document.elementFromPoint(touch.clientX, touch.clientY);
        if (!el || SKIP_TAGS.indexOf(el.tagName) !== -1) return;
        if (el === hoveredEl) return;
        if (hoveredEl) hoveredEl.classList.remove('tsuki-highlight-hover');
        hoveredEl = el;
        if (el !== selectedEl) el.classList.add('tsuki-highlight-hover');
    }, { passive: true });

    /* ── Click / Tap handler ────────────────────────────────────────────────── */

    document.addEventListener('click', function (e) {
        var el = e.target;
        if (!el || SKIP_TAGS.indexOf(el.tagName) !== -1) return;

        e.preventDefault();
        e.stopImmediatePropagation();

        var selector = generateSelector(el);
        if (!selector) return;

        // Clear previous selection highlights
        clearHighlights();
        selectedEl = el;
        el.classList.add('tsuki-highlight-selected');

        // Highlight siblings
        var siblingCount = 0;
        try {
            var siblings = document.querySelectorAll(selector);
            siblingCount = siblings.length;
            for (var i = 0; i < siblings.length; i++) {
                if (siblings[i] !== el) {
                    siblings[i].classList.add('tsuki-highlight-sibling');
                }
            }
        } catch (e2) {}

        // Only-one-match warning passed via selector prefix
        var warning = checkWrongElement(el);
        var cardInfo = findCardContainer(el);
        var cardSelector = cardInfo ? cardInfo.selector : '';
        var cardCount = cardInfo ? cardInfo.count : 0;

        // Communicate to Kotlin
        try {
            window.TsukiPicker.onElementSelected(
                selector,
                el.tagName.toLowerCase(),
                safeText(el),
                safeHtml(el),
                siblingCount,
                warning || '',
                cardSelector,
                cardCount
            );
        } catch (err) {}

    }, true);

    /* ── Expose clearHighlights for Kotlin to call via evaluateJavascript ──── */
    window.tsukiClearHighlights = clearHighlights;

    /* ── Expose highlight-by-selector for review screen ─────────────────────── */
    window.tsukiHighlightSelector = function (selector) {
        clearHighlights();
        if (!selector) return 0;
        try {
            var matches = document.querySelectorAll(selector);
            for (var i = 0; i < matches.length; i++) {
                matches[i].classList.add(i === 0 ? 'tsuki-highlight-selected' : 'tsuki-highlight-sibling');
            }
            return matches.length;
        } catch (e) { return 0; }
    };

})();
