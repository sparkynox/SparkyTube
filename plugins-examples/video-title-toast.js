// ==SparkyPlugin==
// @id          video-title-toast
// @name        Video title toast
// @version     1.0
// @author      SparkyNox
// @description Shows the title of every video that starts playing
// @permissions toast
// ==/SparkyPlugin==

sparky.on('videostart', function (e) {
  if (e.title) sparky.toast(e.title);
});
