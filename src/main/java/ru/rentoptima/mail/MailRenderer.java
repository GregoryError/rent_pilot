package ru.rentoptima.mail;

import org.springframework.web.util.HtmlUtils;

/**
 * HTML и текстовая часть письма. Вёрстка — таблица со встроенными стилями (так её
 * понимают почтовые клиенты), без картинок и внешних ресурсов. Кнопка одна, её адрес
 * повторяется текстом под ней: в части клиентов кнопка не нажимается.
 */
public final class MailRenderer {

    private static final String FONT = "-apple-system,'Segoe UI',Roboto,Arial,sans-serif";

    private MailRenderer() {
    }

    public static String text(Mail mail) {
        StringBuilder sb = new StringBuilder();
        if (mail.heading() != null) sb.append(mail.heading()).append("\n\n");
        for (String p : mail.paragraphs()) sb.append(p).append("\n\n");
        if (mail.buttonUrl() != null) {
            sb.append(mail.buttonLabel()).append(":\n").append(mail.buttonUrl()).append("\n\n");
        }
        if (mail.footer() != null) sb.append("—\n").append(mail.footer()).append("\n");
        return sb.toString().stripTrailing() + "\n";
    }

    public static String html(Mail mail) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"").append(mail.english() ? "en" : "ru").append("\"><head>")
                .append("<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(esc(mail.subject())).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:#f6f5f2;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f6f5f2;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#ffffff;border-radius:12px;\">")
                .append("<tr><td style=\"padding:28px 28px 8px;font-family:").append(FONT)
                .append(";font-size:15px;line-height:1.55;color:#1f1f1f;\">")
                .append("<div style=\"font-weight:600;font-size:14px;color:#6b6b6b;margin-bottom:16px;\">OptiRent</div>");
        if (mail.heading() != null) {
            sb.append("<h1 style=\"font-size:20px;line-height:1.3;margin:0 0 16px;font-weight:600;\">")
                    .append(esc(mail.heading())).append("</h1>");
        }
        for (String p : mail.paragraphs()) {
            sb.append("<p style=\"margin:0 0 14px;\">").append(multiline(p)).append("</p>");
        }
        if (mail.buttonUrl() != null) {
            String url = esc(mail.buttonUrl());
            sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 18px;\"><tr>")
                    .append("<td style=\"border-radius:8px;background:#2f6f4f;\">")
                    .append("<a href=\"").append(url).append("\" style=\"display:inline-block;padding:12px 22px;")
                    .append("font-family:").append(FONT).append(";font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;\">")
                    .append(esc(mail.buttonLabel())).append("</a></td></tr></table>")
                    .append("<p style=\"margin:0 0 14px;font-size:13px;color:#6b6b6b;\">")
                    .append(mail.english() ? "If the button does not work, copy this link into your browser:"
                            : "Если кнопка не нажимается, скопируйте ссылку в браузер:")
                    .append("<br><a href=\"").append(url).append("\" style=\"color:#2f6f4f;word-break:break-all;\">")
                    .append(url).append("</a></p>");
        }
        sb.append("</td></tr>");
        if (mail.footer() != null) {
            sb.append("<tr><td style=\"padding:0 28px 24px;font-family:").append(FONT)
                    .append(";font-size:12px;line-height:1.5;color:#8a8a8a;\">")
                    .append(multiline(mail.footer())).append("</td></tr>");
        }
        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    private static String multiline(String s) {
        return esc(s).replace("\n", "<br>");
    }

    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }
}
