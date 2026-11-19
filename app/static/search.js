(() => {
  const input = document.getElementById('software-search');
  const box = document.getElementById('search-suggestions');
  if (input && box) {
  let timer;
  let controller;
  let activeIndex = -1;
  let results = [];

  const close = () => {
    box.replaceChildren();
    box.hidden = true;
    input.setAttribute('aria-expanded', 'false');
    activeIndex = -1;
    results = [];
  };

  const render = items => {
    results = items;
    activeIndex = -1;
    box.replaceChildren();
    if (!items.length) return close();
    for (const item of items) {
      const option = document.createElement('a');
      option.className = 'search-suggestion';
      option.href = `/software/${encodeURIComponent(item.slug)}`;
      option.setAttribute('role', 'option');
      option.textContent = item.name;
      box.append(option);
    }
    box.hidden = false;
    input.setAttribute('aria-expanded', 'true');
  };

  const load = async () => {
    const query = input.value.trim();
    if (query.length < 2) return close();
    if (controller) controller.abort();
    controller = new AbortController();
    try {
      const response = await fetch(`/api/software-suggestions?q=${encodeURIComponent(query)}`, {
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      });
      if (!response.ok) return close();
      const data = await response.json();
      if (input.value.trim() === query) render(data.items || []);
    } catch (error) {
      if (error.name !== 'AbortError') close();
    }
  };

  input.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(load, 140);
  });
  input.addEventListener('keydown', event => {
    if (box.hidden || !results.length) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      activeIndex = (activeIndex + (event.key === 'ArrowDown' ? 1 : -1) + results.length) % results.length;
      [...box.children].forEach((option, index) => {
        option.setAttribute('aria-selected', String(index === activeIndex));
      });
    } else if (event.key === 'Enter' && activeIndex >= 0) {
      event.preventDefault();
      window.location.href = box.children[activeIndex].href;
    } else if (event.key === 'Escape') close();
  });
  document.addEventListener('click', event => {
    if (!event.target.closest('.search-form')) close();
  });
  input.addEventListener('focus', () => {
    if (input.value.trim().length >= 2 && !box.childElementCount) load();
  });
  }

  const navToggle = document.getElementById("nav-toggle");
  const mainNav = document.getElementById("main-nav");
  if (navToggle && mainNav) {
    const setMenuOpen = open => {
      mainNav.classList.toggle("is-open", open);
      navToggle.setAttribute("aria-expanded", String(open));
      navToggle.setAttribute("aria-label", open ? "بستن منو" : "باز کردن منو");
    };
    navToggle.addEventListener("click", () => setMenuOpen(!mainNav.classList.contains("is-open")));
    mainNav.querySelectorAll("a").forEach(link => link.addEventListener("click", () => setMenuOpen(false)));
    document.addEventListener("click", event => {
      if (!event.target.closest(".topbar")) setMenuOpen(false);
    });
    document.addEventListener("keydown", event => {
      if (event.key === "Escape") setMenuOpen(false);
    });
  }
})();
