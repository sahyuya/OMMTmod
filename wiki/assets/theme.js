/* OMMT User Wiki — theme toggle + TOC highlight. No dependencies. */
(function () {
  "use strict";
  var root = document.documentElement;
  var KEY = "ommt-wiki-theme";

  function systemDark() {
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  }
  function current() {
    return root.getAttribute("data-theme") || "auto";
  }
  function label(mode) {
    return mode === "light" ? "ライト" : mode === "dark" ? "ダーク" : "自動";
  }
  function labelEn(mode) {
    return mode === "light" ? "Light" : mode === "dark" ? "Dark" : "Auto";
  }
  function apply(mode) {
    if (mode === "light" || mode === "dark") {
      root.setAttribute("data-theme", mode);
    } else {
      root.removeAttribute("data-theme");
    }
    try { localStorage.setItem(KEY, mode); } catch (e) { /* private mode */ }
    var btn = document.getElementById("theme-toggle");
    if (btn) {
      var en = root.lang === "en";
      btn.textContent = (en ? "Theme: " : "表示: ") + (en ? labelEn(mode) : label(mode));
    }
  }
  try {
    var saved = localStorage.getItem(KEY);
    if (saved === "light" || saved === "dark" || saved === "auto") apply(saved);
  } catch (e) { /* ignore */ }

  document.addEventListener("DOMContentLoaded", function () {
    var btn = document.getElementById("theme-toggle");
    if (btn) {
      apply(current());
      btn.addEventListener("click", function () {
        var next = current() === "auto" ? (systemDark() ? "light" : "dark")
          : current() === "light" ? "dark" : "auto";
        apply(next);
      });
    }
    // Highlight the TOC entry for the section in view.
    var toc = document.querySelector(".toc");
    var links = toc ? Array.prototype.slice.call(toc.querySelectorAll('a[href^="#"]')) : [];
    if (!links.length || !("IntersectionObserver" in window)) return;
    var map = {};
    links.forEach(function (a) {
      var id = a.getAttribute("href").slice(1);
      var el = document.getElementById(id);
      if (el) map[id] = a;
    });
    var ids = Object.keys(map);
    if (!ids.length) return;
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          links.forEach(function (a) { a.classList.remove("active"); });
          var a = map[entry.target.id];
          if (a) a.classList.add("active");
        }
      });
    }, { rootMargin: "-30% 0px -60% 0px" });
    ids.forEach(function (id) { observer.observe(document.getElementById(id)); });
  });
})();
