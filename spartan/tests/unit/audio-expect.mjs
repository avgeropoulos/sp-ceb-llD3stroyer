// Expected spectral centroid bands (Hz) for every synthesized sound and loop, shared by the
// unit test and the browser check. Wide enough for the variants' randomization, narrow enough
// to catch a sound that turns hissy, boomy or dull.
export const BANDS = {
  whoosh: [1200, 2800], whooshHeavy: [800, 2000], clang: [950, 3500], parry: [1300, 4000], guardBreak: [200, 900],
  flesh: [200, 900], fleshHeavy: [200, 900], wicker: [900, 3000], wood: [180, 900], bash: [150, 700], shieldUp: [800, 3000],
  spearThrow: [800, 2200], spearWhistle: [1000, 2500], spearHit: [250, 900], spearGround: [200, 800], splash: [1200, 4000],
  volley: [2000, 5000], arrowThud: [200, 900], arrowShield: [600, 2500], hurt: [350, 1200], death: [300, 1200],
  grunt: [400, 1200], scream: [600, 2000], warcry: [450, 1500], horn: [250, 900], drum: [80, 400], comboSting: [900, 3000],
  heartbeat: [60, 250], step: [200, 900], uiTap: [600, 2000], uiConfirm: [1200, 4000], draw: [2500, 6000], gull: [1500, 4000],
  title: [300, 900], battle: [300, 900], lastStand: [120, 500], wind: [150, 900], sea: [300, 2500], din: [150, 900], cicadas: [3500, 7500],
};
