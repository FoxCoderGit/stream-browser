package dev.stream.browser;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reports fixed messages without persisting URLs, headers or raw CLI output. */
final class DownloadFailure extends IOException {
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP Error (\\d{3})", Pattern.CASE_INSENSITIVE);
    final String explanation;

    DownloadFailure(String explanation) {
        super(explanation);
        this.explanation = explanation;
    }

    static DownloadFailure http(int status) {
        return new DownloadFailure(switch (status) {
            case 401, 403 -> "Сайт отклонил запрос (HTTP " + status + "). Откройте видео заново и выберите новый источник.";
            case 404, 410 -> "Файл больше недоступен (HTTP " + status + "). Выберите новый источник.";
            case 429 -> "Сайт ограничил число запросов (HTTP 429). Повторите позже.";
            default -> "Сервер вернул HTTP " + status + ".";
        });
    }

    static DownloadFailure extractorLine(String output) {
        String line = output.toLowerCase(Locale.ROOT);
        if (!line.startsWith("error:") && !line.contains("fatal python error")
            && !line.startsWith("cannot link executable")) return null;
        Matcher status = HTTP_STATUS.matcher(output);
        if (status.find()) return http(Integer.parseInt(status.group(1)));
        if (line.contains("sign in") || line.contains("login required") || line.contains("private video")) {
            return new DownloadFailure("Сайт требует входа или подтверждения доступа к видео.");
        }
        if (line.contains("unsupported url") || line.contains("no video formats found")) {
            return new DownloadFailure("По этому адресу не найден доступный поток. Выберите источник в меню «Медиа».");
        }
        if (line.contains("requested format is not available")) {
            return new DownloadFailure("Для этого видео нет выбранного качества. Измените качество и создайте загрузку заново.");
        }
        if (line.contains("drm")) return new DownloadFailure("Поток защищён DRM.");
        if (line.contains("certificate")) return new DownloadFailure("Сертификат сайта не прошёл проверку.");
        if (line.contains("ffmpeg")) return new DownloadFailure("FFmpeg не смог обработать или объединить потоки.");
        if (line.contains("fatal python") || line.contains("cannot link executable")) {
            return new DownloadFailure("Не удалось запустить встроенный загрузчик.");
        }
        return null;
    }
}
