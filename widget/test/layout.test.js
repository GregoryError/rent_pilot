import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolve, launcher } from '../src/layout.js';
import { contrast, mix, palette } from '../src/theme.js';

const all = () => true;
const without = (...ids) => id => !ids.includes(id);

test('split на широком контейнере: содержимое слева, бронирование справа, форма «прилипает»', () => {
    const l = resolve({ preset: 'split' }, true, all);
    assert.deepEqual(l.top, []);
    assert.deepEqual(l.cols, [
        ['gallery', 'title', 'description', 'amenities', 'rules', 'contacts', 'map'],
        ['calendar', 'guests', 'summary', 'form']]);
    assert.equal(l.areas, '"c0 c1"');
    assert.equal(l.columns, 'minmax(0,1fr) min(420px,44%)');
    assert.equal(l.sticky, 1);
    assert.equal(l.max, null);
});

test('split на узком контейнере и vertical — одна колонка, галерея сверху', () => {
    for (const l of [resolve({ preset: 'split' }, false, all), resolve({ preset: 'vertical' }, true, all)]) {
        assert.deepEqual(l.top, ['gallery']);
        assert.deepEqual(l.cols, [['title', 'calendar', 'guests', 'summary', 'form',
            'description', 'amenities', 'rules', 'contacts', 'map']]);
        assert.equal(l.areas, '"t0" "c0"');
        assert.equal(l.max, 680);
        assert.equal(l.sticky, -1);
    }
});

test('split без фото и описания слева был бы пустым — раскладывается одной колонкой', () => {
    const l = resolve({ preset: 'split' }, true, without('gallery', 'description', 'amenities'));
    assert.equal(l.cols.length, 1);
    assert.deepEqual(l.top, []);
    assert.equal(l.areas, '"c0"');
});

test('блоки без содержимого и скрытые хозяином в сетку не попадают', () => {
    const l = resolve({ preset: 'split' }, true, without('map', 'contacts', 'rules'));
    assert.deepEqual(l.cols[0], ['gallery', 'title', 'description', 'amenities']);
});

test('custom: до трёх колонок и блоки во всю ширину на широком, свой порядок на узком', () => {
    const custom = {
        top: ['gallery', 'title'],
        cols: [['calendar'], ['guests', 'summary', 'form'], ['description', 'map']],
        mobile: ['title', 'calendar', 'guests', 'summary', 'form', 'gallery', 'description', 'map']
    };
    const wide = resolve({ preset: 'custom', custom }, true, without('map'));
    assert.deepEqual(wide.top, ['gallery', 'title']);
    assert.deepEqual(wide.cols, [['calendar'], ['guests', 'summary', 'form'], ['description']]);
    assert.equal(wide.areas, '"t0 t0 t0" "t1 t1 t1" "c0 c1 c2"');
    assert.equal(wide.sticky, 1);

    const narrow = resolve({ preset: 'custom', custom }, false, without('map'));
    assert.deepEqual(narrow.cols, [['title', 'calendar', 'guests', 'summary', 'form', 'gallery', 'description']]);
    assert.equal(narrow.areas, '"c0"');
});

test('custom: опустевшая колонка исчезает, а не оставляет дыру', () => {
    const custom = { top: [], cols: [['gallery'], ['calendar', 'guests', 'summary', 'form']], mobile: [] };
    const l = resolve({ preset: 'custom', custom }, true, without('gallery'));
    assert.equal(l.cols.length, 1);
    assert.equal(l.areas, '"c0"');
});

test('пресет не задан или custom без расстановки — как split', () => {
    assert.equal(resolve(null, true, all).cols.length, 2);
    assert.equal(resolve({ preset: 'custom' }, true, all).cols.length, 1);
});

test('horizontal и compact — пусковая полоса, внутри окна раскладка в одну колонку', () => {
    assert.ok(launcher('horizontal') && launcher('compact'));
    assert.ok(!launcher('split') && !launcher('custom'));
    assert.equal(resolve({ preset: 'compact' }, true, all).cols.length, 1);
});

test('контраст и палитра: текст на кнопке читается на любом цвете', () => {
    assert.equal(Math.round(contrast('#000000', '#ffffff')), 21);
    assert.equal(mix('#000000', '#ffffff', 0.5), '#808080');
    for (const accent of ['#0f766e', '#ffe600', '#ffffff', '#000000', '#ff5a5f', '#7c3aed', '#9ca3af']) {
        for (const mode of ['light', 'dark']) {
            const p = palette(accent, mode);
            const bg = mode === 'dark' ? '#17181b' : '#ffffff';
            assert.ok(contrast(p.fg, accent) >= 4.5, `кнопка ${accent} ${mode}: ${contrast(p.fg, accent)}`);
            assert.ok(contrast(p.text, bg) >= 4.5, `ссылка ${accent} ${mode}: ${contrast(p.text, bg)}`);
        }
    }
    // Жёлтая кнопка — чёрный текст, тёмная — белый
    assert.equal(palette('#ffe600', 'light').fg, '#111111');
    assert.equal(palette('#0f766e', 'light').fg, '#ffffff');
    // Цвет, который и так читается на фоне, для ссылок не меняется
    assert.equal(palette('#0f766e', 'light').text, '#0f766e');
});
