package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * Writes a WebView's print output straight to a PDF file, without showing the system print dialog.
 *
 * The result callbacks of {@link PrintDocumentAdapter} can only be created from inside the
 * {@code android.print} package, which is why this small class lives here. If a phone does not
 * allow it, the caller catches the error and falls back to the normal print dialog
 * ("Save as PDF").
 */
public final class PdfPrinter {

    public interface Callback {
        void onSuccess(File file);

        void onFailure(String message);
    }

    private PdfPrinter() {
    }

    public static void print(final PrintDocumentAdapter adapter, final PrintAttributes attributes,
                             final File outFile, final Callback callback) {
        adapter.onStart();
        adapter.onLayout(null, attributes, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                final ParcelFileDescriptor pfd;
                try {
                    pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
                } catch (IOException e) {
                    adapter.onFinish();
                    callback.onFailure(String.valueOf(e.getMessage()));
                    return;
                }
                adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, pfd, new CancellationSignal(),
                        new PrintDocumentAdapter.WriteResultCallback() {
                            @Override
                            public void onWriteFinished(PageRange[] pages) {
                                close(pfd);
                                adapter.onFinish();
                                if (outFile.length() > 0) {
                                    callback.onSuccess(outFile);
                                } else {
                                    callback.onFailure("empty PDF");
                                }
                            }

                            @Override
                            public void onWriteFailed(CharSequence error) {
                                close(pfd);
                                adapter.onFinish();
                                callback.onFailure(String.valueOf(error));
                            }

                            @Override
                            public void onWriteCancelled() {
                                close(pfd);
                                adapter.onFinish();
                                callback.onFailure("cancelled");
                            }
                        });
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                adapter.onFinish();
                callback.onFailure(String.valueOf(error));
            }

            @Override
            public void onLayoutCancelled() {
                adapter.onFinish();
                callback.onFailure("cancelled");
            }
        }, null);
    }

    private static void close(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
