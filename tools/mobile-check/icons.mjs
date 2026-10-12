// Иконки сайта из favicon.svg: PNG для iOS и манифеста, favicon.ico для старых браузеров.
//   node icons.mjs   (из tools/mobile-check, нужен Chrome)
// Запускать заново, только если поменялся логотип.
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';

const STATIC = new URL('../../src/main/resources/static/', import.meta.url).pathname;
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const svg = fs.readFileSync(STATIC + 'favicon.svg', 'utf8');
// Для iOS и «маскируемой» иконки Android — квадрат без скруглений и прозрачности: углы режет система
const square = svg.replace('rx="6"', 'rx="0"');

const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new' });
const page = await browser.newPage();
async function png(source, size, file) {
  await page.setViewport({ width: size, height: size, deviceScaleFactor: 1 });
  await page.setContent(`<style>html,body{margin:0;background:transparent}svg{display:block;width:${size}px;height:${size}px}</style>${source}`);
  const buf = await page.screenshot({ omitBackground: true, type: 'png' });
  if (file) fs.writeFileSync(STATIC + file, buf);
  return buf;
}
await png(square, 180, 'apple-touch-icon.png');
await png(svg, 192, 'icons/icon-192.png');
await png(svg, 512, 'icons/icon-512.png');
await png(square, 512, 'icons/icon-maskable-512.png');

// favicon.ico: контейнер ICO с PNG внутри (16, 32, 48) — так его читают все современные браузеры
const sizes = [16, 32, 48];
const images = [];
for (const s of sizes) images.push(await png(svg, s));
const header = Buffer.alloc(6 + 16 * sizes.length);
header.writeUInt16LE(0, 0); header.writeUInt16LE(1, 2); header.writeUInt16LE(sizes.length, 4);
let offset = header.length;
sizes.forEach((s, i) => {
  const e = 6 + 16 * i;
  header.writeUInt8(s, e); header.writeUInt8(s, e + 1); header.writeUInt8(0, e + 2); header.writeUInt8(0, e + 3);
  header.writeUInt16LE(1, e + 4); header.writeUInt16LE(32, e + 6);
  header.writeUInt32LE(images[i].length, e + 8); header.writeUInt32LE(offset, e + 12);
  offset += images[i].length;
});
fs.writeFileSync(STATIC + 'favicon.ico', Buffer.concat([header, ...images]));
await browser.close();
console.log('Иконки записаны в', STATIC);
