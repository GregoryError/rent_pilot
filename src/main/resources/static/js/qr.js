// QR-коды для ссылок: предпросмотр и скачивание в SVG / PNG / JPEG прямо в браузере.
// Разметка: <div data-qr="https://…" data-qr-name="имя-файла"> с .qr__preview внутри
// и кнопками <button data-qr-download="svg|png|jpeg">.
(function () {
    var QUIET = 4;          // обязательное белое поле вокруг кода, в модулях
    var RASTER_SIZE = 1024; // сторона PNG/JPEG в пикселях — хватает для печати на А5

    function matrix(text) {
        var qr = qrcode(0, 'M'); // версия подбирается сама; M — восстановление до 15% повреждений
        qr.addData(text);
        qr.make();
        var n = qr.getModuleCount();
        var rows = [];
        for (var r = 0; r < n; r++) {
            var row = [];
            for (var c = 0; c < n; c++) row.push(qr.isDark(r, c));
            rows.push(row);
        }
        return rows;
    }

    function toSvg(rows) {
        var size = rows.length + QUIET * 2;
        var path = '';
        for (var r = 0; r < rows.length; r++) {
            for (var c = 0; c < rows.length; c++) {
                if (rows[r][c]) path += 'M' + (c + QUIET) + ' ' + (r + QUIET) + 'h1v1h-1z';
            }
        }
        return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + size + ' ' + size
            + '" width="' + RASTER_SIZE + '" height="' + RASTER_SIZE + '" shape-rendering="crispEdges">'
            + '<rect width="' + size + '" height="' + size + '" fill="#fff"/>'
            + '<path d="' + path + '" fill="#000"/></svg>';
    }

    function toCanvas(rows) {
        var size = rows.length + QUIET * 2;
        var scale = Math.max(1, Math.floor(RASTER_SIZE / size)); // целый масштаб — без размытых границ
        var canvas = document.createElement('canvas');
        canvas.width = canvas.height = size * scale;
        var ctx = canvas.getContext('2d');
        ctx.fillStyle = '#fff';   // белый фон обязателен: у JPEG нет прозрачности
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.fillStyle = '#000';
        for (var r = 0; r < rows.length; r++) {
            for (var c = 0; c < rows.length; c++) {
                if (rows[r][c]) ctx.fillRect((c + QUIET) * scale, (r + QUIET) * scale, scale, scale);
            }
        }
        return canvas;
    }

    function save(blob, filename) {
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    }

    function fileName(box, ext) {
        var base = (box.dataset.qrName || 'qr').trim()
            .replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, '-').slice(0, 60) || 'qr';
        return base + '.' + ext;
    }

    document.querySelectorAll('[data-qr]').forEach(function (box) {
        var rows;
        try {
            rows = matrix(box.dataset.qr);
        } catch (e) {
            box.style.display = 'none'; // ссылка длиннее, чем помещается в QR
            return;
        }
        var preview = box.querySelector('.qr__preview');
        if (preview) preview.innerHTML = toSvg(rows);

        box.addEventListener('click', function (e) {
            var btn = e.target.closest('[data-qr-download]');
            if (!btn) return;
            var format = btn.dataset.qrDownload;
            if (format === 'svg') {
                save(new Blob([toSvg(rows)], { type: 'image/svg+xml' }), fileName(box, 'svg'));
                return;
            }
            var jpeg = format === 'jpeg';
            toCanvas(rows).toBlob(function (blob) {
                if (blob) save(blob, fileName(box, jpeg ? 'jpg' : 'png'));
            }, jpeg ? 'image/jpeg' : 'image/png', 0.95);
        });
    });

    // Для проверки вне браузера
    if (typeof window !== 'undefined') window.OptiRentQr = { matrix: matrix, toSvg: toSvg };
})();
