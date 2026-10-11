// ==SparkyPlugin==
// @id          hide-shorts
// @name        Hide Shorts
// @version     1.0
// @author      SparkyNox
// @description Removes the Shorts shelves and the Shorts tab from the YouTube page
// ==/SparkyPlugin==

sparky.addStyle(`
  ytm-reel-shelf-renderer,
  ytm-shorts-lockup-view-model,
  ytm-rich-section-renderer:has(ytm-reel-shelf-renderer),
  a[href^="/shorts"] { display: none !important; }
`);
