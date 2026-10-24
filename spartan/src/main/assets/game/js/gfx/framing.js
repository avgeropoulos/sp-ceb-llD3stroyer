/**
 * The world camera's field of view per aspect (docs-hilt-grip.md §E.1, DESIGN.md §15). PURE: no
 * DOM, no three.js.
 *
 * Landscape keeps a fixed ~100° horizontal FOV: a fixed 70° vertical FOV is ~114° across a wide
 * phone, which shrinks the enemies and stretches the edges. Portrait (the hilt grip) keeps ~46°
 * across, so a foe at 1.8 m fills about half the width, and the lock-on keeps him centred.
 */

/** Horizontal targets and vertical clamps, in degrees. */
export const FOV = Object.freeze({
  landscapeH: 100,
  landscapeV: Object.freeze([55, 70]),
  portraitH: 46,
  portraitV: Object.freeze([72, 84]),
});

/**
 * The vertical FOV that shows `hDeg` across at this aspect (width / height).
 * @param {number} hDeg horizontal FOV in degrees
 * @param {number} aspect width / height
 * @returns {number} vertical FOV in degrees
 */
export function verticalFov(hDeg, aspect) {
  return (2 * Math.atan(Math.tan((hDeg * Math.PI) / 360) / aspect) * 180) / Math.PI;
}

/**
 * The horizontal FOV that a vertical FOV of `vDeg` shows at this aspect.
 * @param {number} vDeg vertical FOV in degrees
 * @param {number} aspect width / height
 * @returns {number} horizontal FOV in degrees
 */
export function horizontalFov(vDeg, aspect) {
  return (2 * Math.atan(Math.tan((vDeg * Math.PI) / 360) * aspect) * 180) / Math.PI;
}

/**
 * The world camera's vertical FOV in degrees (main.js applySize).
 *   aspect ≥ 1: clamp(verticalFov(100), 55, 70), the landscape formula of before, same numbers;
 *   aspect < 1: clamp(verticalFov(46), 72, 84), e.g. 84° × 44° on a 412×915 phone, 80.7° × 46° at
 *   9:18 and 72° × 57° on a portrait tablet (0.75).
 * A degenerate aspect (0, NaN) gives 70, the camera's construction value.
 * @param {number} aspect width / height
 * @returns {number}
 */
export function cameraFov(aspect) {
  if (!(aspect > 0)) return FOV.landscapeV[1];
  const landscape = aspect >= 1;
  const v = verticalFov(landscape ? FOV.landscapeH : FOV.portraitH, aspect);
  const range = landscape ? FOV.landscapeV : FOV.portraitV;
  return v < range[0] ? range[0] : v > range[1] ? range[1] : v;
}
