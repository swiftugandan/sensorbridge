// The hero phone leans toward the pointer, like a board being tilted. Off for reduced motion and touch-only
// devices, where there is no hover to follow.
(() => {
  const phone = document.querySelector("[data-tilt]");
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const finePointer = window.matchMedia("(hover: hover) and (pointer: fine)");
  if (!phone || reduceMotion.matches || !finePointer.matches) return;

  const rest = "rotateY(-14deg) rotateX(6deg) rotateZ(2deg)";
  let frame = 0;

  window.addEventListener("pointermove", (event) => {
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(() => {
      const x = event.clientX / window.innerWidth - 0.5;
      const y = event.clientY / window.innerHeight - 0.5;
      phone.style.transform = `rotateY(${(-14 + x * 22).toFixed(2)}deg) rotateX(${(6 - y * 16).toFixed(2)}deg) rotateZ(2deg)`;
    });
  });
  document.addEventListener("pointerleave", () => {
    cancelAnimationFrame(frame);
    phone.style.transform = rest;
  });
})();
