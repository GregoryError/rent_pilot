// Сборка виджета бронирования: widget/src → src/main/resources/static/w.js
//
//   cd widget && npm install && npm run build
//
// Бандл один: JS и CSS (CSS вшит строкой — он живёт в Shadow DOM компонента).
// В первой строке бандла — хэш исходников. WidgetBundleTest (mvn test) пересчитывает
// его и падает, если w.js собран не из текущих исходников. Собранный файл лежит в
// репозитории: docker-сборка приложения про node ничего не знает.
import { build, transform } from 'esbuild';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const out = join(root, '../src/main/resources/static/w.js');

// Бюджет из требований к виджету, gzip
const JS_BUDGET = 35 * 1024;
const CSS_BUDGET = 15 * 1024;

/** Хэш исходников: тот же алгоритм в WidgetBundleTest.sourceHash(). */
function sourceHash() {
    const files = ['build.mjs', ...readdirSync(join(root, 'src')).sort().map(f => 'src/' + f)];
    const hash = createHash('sha256');
    for (const file of files) {
        hash.update(file).update('\n').update(readFileSync(join(root, file))).update('\n');
    }
    return hash.digest('hex');
}

const css = (await transform(readFileSync(join(root, 'src/styles.css'), 'utf8'),
    { loader: 'css', minify: true })).code.trim();

const result = await build({
    entryPoints: [join(root, 'src/index.js')],
    bundle: true,
    minify: true,
    format: 'iife',
    target: 'es2020',
    write: false,
    legalComments: 'none',
    plugins: [{
        name: 'inline-css',
        setup(b) {
            b.onLoad({ filter: /styles\.css$/ }, () => ({ contents: css, loader: 'text' }));
        }
    }]
});

const js = result.outputFiles[0].text;
const header = `/*! OptiRent booking widget. Собран из widget/src — руками не править. src:${sourceHash()} */\n`;
writeFileSync(out, header + js);

const gz = s => gzipSync(Buffer.from(s)).length;
const cssGz = gz(css);
const totalGz = gz(header + js);
const jsGz = totalGz - cssGz;
const kb = n => (n / 1024).toFixed(1) + ' КБ';
console.log(`w.js: ${kb((header + js).length)} (gzip ${kb(totalGz)})`);
console.log(`  JS  ≈ ${kb(jsGz)} gzip, бюджет ${kb(JS_BUDGET)}`);
console.log(`  CSS = ${kb(cssGz)} gzip, бюджет ${kb(CSS_BUDGET)}`);
if (jsGz > JS_BUDGET || cssGz > CSS_BUDGET) {
    console.error('Бюджет размера превышен');
    process.exit(1);
}
