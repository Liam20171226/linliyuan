/** 与服务端 GET /regions 同源（全国 31 省市区，不含港澳台）；接口失败时用本地副本 */
function tree() {
  // 微信小程序不能 require .json，需用 .js 模块
  return require('./regions-data.js')
}

module.exports = { tree }
