// ==SparkyPlugin==
// @id          watch-counter
// @name        Watch counter
// @version     1.0
// @author      SparkyNox
// @description Counts the videos you start and says so every 10th one
// @permissions toast
// ==/SparkyPlugin==

sparky.on('videostart', function () {
  var n = sparky.storage.get('count', 0) + 1;
  sparky.storage.set('count', n);
  if (n % 10 === 0) sparky.toast(n + ' videos so far');
});
