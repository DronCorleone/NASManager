package io.github.nasmanager;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sandboxed, dependency-free renderer for the path-based SVG icons used by the catalog. */
final class SvgIconDecoder {
    private static final int SIZE = 144;
    private static final int MAX_ELEMENTS = 10_000;
    private static final int MAX_PATH_LENGTH = 256 * 1024;
    private static final int UNRESOLVED_COLOR = Color.rgb(55, 199, 190);

    private SvgIconDecoder() { }

    static Bitmap decode(byte[] encoded) {
        try {
            String xml = new String(encoded, StandardCharsets.UTF_8);
            String lower = xml.toLowerCase(Locale.US);
            // XmlPullParser does not need DTDs here. Reject them before parsing so
            // external entities and XML stylesheets can never cause I/O.
            if (lower.contains("<!doctype") || lower.contains("<!entity")
                    || lower.contains("<?xml-stylesheet") || lower.contains("<script")) return null;
            Map<String, Map<String, String>> css = parseCss(xml);
            Map<String, Integer> gradients = parseGradients(xml);

            XmlPullParser parser = Xml.newPullParser();
            try { parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false); }
            catch (Exception ignored) { }
            parser.setInput(new ByteArrayInputStream(encoded), "UTF-8");

            Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            ArrayDeque<Style> styles = new ArrayDeque<>();
            styles.push(new Style());
            int definitions = 0;
            int elements = 0;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (++elements > MAX_ELEMENTS) return null;
                    String tag = parser.getName();
                    if ("svg".equals(tag)) {
                        setupViewport(canvas, parser);
                        applyStyle(styles.peek(), parser, css);
                    }
                    if ("defs".equals(tag) || "clipPath".equals(tag) || "mask".equals(tag)) definitions++;
                    if ("g".equals(tag)) {
                        Style inherited = new Style(styles.peek());
                        applyStyle(inherited, parser, css);
                        styles.push(inherited);
                        canvas.save();
                        applyTransform(canvas, attr(parser, "transform", ""));
                    } else if (definitions == 0 && isShape(tag)) {
                        Style style = new Style(styles.peek());
                        applyStyle(style, parser, css);
                        canvas.save();
                        applyTransform(canvas, attr(parser, "transform", ""));
                        drawShape(canvas, parser, tag, style, gradients);
                        canvas.restore();
                    }
                    // image/use/foreignObject and every unknown tag are ignored;
                    // external URLs, JavaScript and embedded documents never run.
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("g".equals(tag)) {
                        canvas.restore();
                        styles.pop();
                    }
                    if ("defs".equals(tag) || "clipPath".equals(tag) || "mask".equals(tag)) definitions--;
                }
            }
            return bitmap;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void setupViewport(Canvas canvas, XmlPullParser parser) {
        String[] box = attr(parser, "viewBox", "").trim().split("[,\\s]+");
        float minX = 0, minY = 0;
        float width = number(attr(parser, "width", String.valueOf(SIZE)), SIZE);
        float height = number(attr(parser, "height", String.valueOf(SIZE)), SIZE);
        if (box.length == 4) {
            minX = number(box[0], 0); minY = number(box[1], 0);
            width = number(box[2], width); height = number(box[3], height);
        }
        if (width <= 0 || height <= 0 || !Float.isFinite(width) || !Float.isFinite(height)) return;
        float scale = Math.min(SIZE / width, SIZE / height);
        canvas.translate((SIZE - width * scale) / 2, (SIZE - height * scale) / 2);
        canvas.scale(scale, scale);
        canvas.translate(-minX, -minY);
    }

    private static boolean isShape(String tag) {
        return "path".equals(tag) || "rect".equals(tag) || "circle".equals(tag)
                || "ellipse".equals(tag) || "line".equals(tag)
                || "polygon".equals(tag) || "polyline".equals(tag);
    }

    private static void drawShape(Canvas canvas, XmlPullParser parser, String tag, Style style,
                                  Map<String, Integer> gradients) {
        Path path = new Path();
        if ("path".equals(tag)) {
            String data = attr(parser, "d", "");
            if (data.length() > MAX_PATH_LENGTH) return;
            Path parsed = SvgPathDataParser.parse(data);
            if (parsed == null) return;
            path = parsed;
        } else if ("rect".equals(tag)) {
            float x = number(attr(parser, "x", "0"), 0), y = number(attr(parser, "y", "0"), 0);
            float width = number(attr(parser, "width", "0"), 0), height = number(attr(parser, "height", "0"), 0);
            float rx = number(attr(parser, "rx", "0"), 0), ry = number(attr(parser, "ry", String.valueOf(rx)), rx);
            path.addRoundRect(new RectF(x, y, x + width, y + height), rx, ry, Path.Direction.CW);
        } else if ("circle".equals(tag)) {
            path.addCircle(number(attr(parser, "cx", "0"), 0), number(attr(parser, "cy", "0"), 0),
                    number(attr(parser, "r", "0"), 0), Path.Direction.CW);
        } else if ("ellipse".equals(tag)) {
            float cx = number(attr(parser, "cx", "0"), 0), cy = number(attr(parser, "cy", "0"), 0);
            float rx = number(attr(parser, "rx", "0"), 0), ry = number(attr(parser, "ry", "0"), 0);
            path.addOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW);
        } else if ("line".equals(tag)) {
            path.moveTo(number(attr(parser, "x1", "0"), 0), number(attr(parser, "y1", "0"), 0));
            path.lineTo(number(attr(parser, "x2", "0"), 0), number(attr(parser, "y2", "0"), 0));
        } else {
            String raw = attr(parser, "points", "").trim();
            if (raw.isEmpty() || raw.length() > MAX_PATH_LENGTH) return;
            String[] points = raw.split("[,\\s]+");
            for (int i = 0; i + 1 < points.length; i += 2) {
                float x = number(points[i], 0), y = number(points[i + 1], 0);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            if ("polygon".equals(tag)) path.close();
        }
        path.setFillType("evenodd".equalsIgnoreCase(style.fillRule)
                ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
        drawPath(canvas, path, style, gradients);
    }

    private static void drawPath(Canvas canvas, Path path, Style style, Map<String, Integer> gradients) {
        if (!"none".equalsIgnoreCase(style.fill)) {
            Paint fill = paint(Paint.Style.FILL, style.fill, style.opacity * style.fillOpacity, gradients);
            canvas.drawPath(path, fill);
        }
        if (!"none".equalsIgnoreCase(style.stroke)) {
            Paint stroke = paint(Paint.Style.STROKE, style.stroke, style.opacity * style.strokeOpacity, gradients);
            stroke.setStrokeWidth(style.strokeWidth);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, stroke);
        }
    }

    private static Paint paint(Paint.Style style, String value, float opacity, Map<String, Integer> gradients) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(style);
        int color = resolveColor(value, gradients);
        paint.setColor(color);
        paint.setAlpha(Math.max(0, Math.min(255, Math.round(opacity * Color.alpha(color)))));
        return paint;
    }

    private static int resolveColor(String value, Map<String, Integer> gradients) {
        if (value != null && value.startsWith("url(")) {
            int hash = value.indexOf('#'), end = value.indexOf(')', hash);
            if (hash >= 0 && end > hash) {
                String id = value.substring(hash + 1, end).replace("'", "").replace("\"", "");
                Integer color = gradients.get(id);
                if (color != null) return color;
            }
            return UNRESOLVED_COLOR;
        }
        return parseColor(value, UNRESOLVED_COLOR);
    }

    private static void applyStyle(Style result, XmlPullParser parser,
                                   Map<String, Map<String, String>> css) {
        Map<String, String> values = new HashMap<>();
        for (String className : attr(parser, "class", "").split("\\s+")) {
            Map<String, String> rule = css.get(className);
            if (rule != null) values.putAll(rule);
        }
        values.putAll(properties(attr(parser, "style", "")));
        for (String name : new String[]{"fill", "stroke", "fill-rule", "opacity", "fill-opacity", "stroke-opacity", "stroke-width"}) {
            String direct = attr(parser, name, "");
            if (!direct.isEmpty()) values.put(name, direct);
        }
        if (values.containsKey("fill")) result.fill = values.get("fill");
        if (values.containsKey("stroke")) result.stroke = values.get("stroke");
        if (values.containsKey("fill-rule")) result.fillRule = values.get("fill-rule");
        if (values.containsKey("opacity")) result.opacity *= number(values.get("opacity"), 1);
        if (values.containsKey("fill-opacity")) result.fillOpacity *= number(values.get("fill-opacity"), 1);
        if (values.containsKey("stroke-opacity")) result.strokeOpacity *= number(values.get("stroke-opacity"), 1);
        if (values.containsKey("stroke-width")) result.strokeWidth = number(values.get("stroke-width"), result.strokeWidth);
    }

    private static Map<String, Map<String, String>> parseCss(String xml) {
        Map<String, Map<String, String>> result = new HashMap<>();
        Matcher matcher = Pattern.compile("\\.([A-Za-z0-9_-]+)\\s*\\{([^}]*)\\}").matcher(xml);
        while (matcher.find()) result.put(matcher.group(1), properties(matcher.group(2)));
        return result;
    }

    private static Map<String, Integer> parseGradients(String xml) {
        Map<String, Integer> result = new HashMap<>();
        Matcher gradient = Pattern.compile(
                "<(?:linearGradient|radialGradient)[^>]*id=[\"']([^\"']+)[\"'][^>]*>(.*?)</(?:linearGradient|radialGradient)>",
                Pattern.DOTALL).matcher(xml);
        while (gradient.find()) {
            Matcher stop = Pattern.compile("stop-color\\s*[:=]\\s*[\"']?([^;\"'\\s>]+)")
                    .matcher(gradient.group(2));
            if (stop.find()) result.put(gradient.group(1), parseColor(stop.group(1), UNRESOLVED_COLOR));
        }
        return result;
    }

    private static Map<String, String> properties(String raw) {
        Map<String, String> result = new HashMap<>();
        for (String entry : raw.split(";")) {
            int colon = entry.indexOf(':');
            if (colon > 0) result.put(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
        }
        return result;
    }

    private static void applyTransform(Canvas canvas, String value) {
        Matrix combined = new Matrix();
        Matcher matcher = Pattern.compile("([a-zA-Z]+)\\s*\\(([^)]*)\\)").matcher(value == null ? "" : value);
        while (matcher.find()) {
            String operation = matcher.group(1).toLowerCase(Locale.US);
            String raw = matcher.group(2).trim();
            if (raw.isEmpty()) continue;
            String[] parts = raw.split("[,\\s]+");
            float[] n = new float[parts.length];
            for (int i = 0; i < parts.length; i++) n[i] = number(parts[i], 0);
            Matrix step = new Matrix();
            if ("translate".equals(operation) && n.length >= 1) step.setTranslate(n[0], n.length > 1 ? n[1] : 0);
            else if ("scale".equals(operation) && n.length >= 1) step.setScale(n[0], n.length > 1 ? n[1] : n[0]);
            else if ("rotate".equals(operation) && n.length >= 1) {
                if (n.length >= 3) step.setRotate(n[0], n[1], n[2]); else step.setRotate(n[0]);
            } else if ("matrix".equals(operation) && n.length == 6) {
                step.setValues(new float[]{n[0], n[2], n[4], n[1], n[3], n[5], 0, 0, 1});
            } else continue;
            combined.postConcat(step);
        }
        canvas.concat(combined);
    }

    private static String attr(XmlPullParser parser, String name, String fallback) {
        String result = parser.getAttributeValue(null, name);
        return result == null ? fallback : result;
    }

    private static float number(String value, float fallback) {
        try {
            if (value == null) return fallback;
            return Float.parseFloat(value.trim().replaceAll("(?i)(px|pt|em|rem|%)$", ""));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int parseColor(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String color = value.trim();
        try {
            if (color.matches("#[0-9a-fA-F]{3}")) {
                char r = color.charAt(1), g = color.charAt(2), b = color.charAt(3);
                color = "#" + r + r + g + g + b + b;
            } else if (color.matches("#[0-9a-fA-F]{6}[0-9a-fA-F]{2}")) {
                color = "#" + color.substring(7, 9) + color.substring(1, 7);
            }
            return Color.parseColor(color);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class Style {
        String fill = "black", stroke = "none", fillRule = "nonzero";
        float opacity = 1, fillOpacity = 1, strokeOpacity = 1, strokeWidth = 1;
        Style() { }
        Style(Style other) {
            fill = other.fill; stroke = other.stroke; fillRule = other.fillRule;
            opacity = other.opacity; fillOpacity = other.fillOpacity;
            strokeOpacity = other.strokeOpacity; strokeWidth = other.strokeWidth;
        }
    }
}
