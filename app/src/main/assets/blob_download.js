async function downloadGeminiBlob(url, token, maxBytes) {
        const channel = window.GeminiDownload;
        if (!channel) return;
        const send = message => new Promise((resolve, reject) => {
            const timeout = setTimeout(() => reject(new Error('Download timeout')), 30000);
            channel.onmessage = event => {
                try {
                    const result = JSON.parse(event.data);
                    if (result.token !== token) return;
                    clearTimeout(timeout);
                    result.ok ? resolve() : reject(new Error('Download failed'));
                } catch (error) { clearTimeout(timeout); reject(error); }
            };
            channel.postMessage(JSON.stringify(Object.assign({token}, message)));
        });
        try {
            const response = await fetch(url);
            if (!response.ok) throw new Error('Download failed');
            const blob = await response.blob();
            if (blob.size > maxBytes) throw new Error('File too large');
            await send({type: 'begin', size: blob.size, mime: blob.type});
            for (let offset = 0; offset < blob.size; offset += 49152) {
                const bytes = new Uint8Array(await blob.slice(offset, offset + 49152).arrayBuffer());
                const data = btoa(String.fromCharCode.apply(null, bytes));
                await send({type: 'chunk', data});
            }
            await send({type: 'end'});
        } catch (error) {
            try { await send({type: 'abort'}); } catch (_) {}
        } finally {
            channel.onmessage = null;
        }
}
