/* SPDX-License-Identifier: GPL-2.0-or-later
 * Software-frame adapter for Boxedwine, based on its boxedwine-shell.js presenter.
 * Copyright (C) 2012-2026 The BoxedWine Team; Rétro Iso adaptation 2026.
 * Source: https://github.com/danoon2/Boxedwine/blob/65467424603484756a73308412f40b906d28b650/project/emscripten/boxedwine-shell.js
 */
export function softwarePresenter(canvas) {
  let context;
  return (width, height, buffer, presentState) => {
    try {
      if (!buffer || width < 1 || height < 1) return;
      if (canvas.width !== width) canvas.width = width;
      if (canvas.height !== height) canvas.height = height;
      context ||= canvas.getContext('2d');
      if (!context) throw new Error('Affichage 2D indisponible.');
      context.putImageData(new ImageData(new Uint8ClampedArray(buffer), width, height), 0, 0);
      canvas.style.visibility = 'visible';
    } finally {
      if (presentState && typeof Atomics !== 'undefined') Atomics.store(presentState, 0, 0);
    }
  };
}
export function configureGLCanvas(canvas) {
  const getContext = canvas.getContext.bind(canvas);
  canvas.getContext = (type, attributes) => getContext(type, /^(webgl2?|experimental-webgl)$/.test(type) ? {...attributes, preserveDrawingBuffer: true} : attributes);
}
