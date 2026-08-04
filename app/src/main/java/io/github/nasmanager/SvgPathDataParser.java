package io.github.nasmanager;

import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal SVG path-data parser supporting the standard drawing commands, including arcs. */
final class SvgPathDataParser {
    private static final Pattern TOKEN = Pattern.compile(
            "([MmZzLlHhVvCcSsQqTtAa])|([-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?)");

    private SvgPathDataParser() { }

    static Path parse(String data) {
        if (data == null) return null;
        try {
            Path path = new Path();
            State state = new State();
            Matcher matcher = TOKEN.matcher(data);
            char command = 0;
            List<Float> values = new ArrayList<>();
            while (matcher.find()) {
                if (matcher.group(1) != null) {
                    if (command != 0) execute(path, state, command, values);
                    command = matcher.group(1).charAt(0);
                    values.clear();
                    if (command == 'Z' || command == 'z') {
                        execute(path, state, command, values);
                        command = 0;
                    }
                } else if (command != 0) {
                    values.add(Float.parseFloat(matcher.group(2)));
                }
            }
            if (command != 0) execute(path, state, command, values);
            return path;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void execute(Path path, State s, char command, List<Float> v) {
        int count = parameterCount(command);
        if (count == 0) {
            path.close();
            s.x = s.startX; s.y = s.startY; s.controlX = s.x; s.controlY = s.y; s.previous = command;
            return;
        }
        if (v.size() % count != 0) throw new IllegalArgumentException("Invalid path data");
        for (int i = 0; i < v.size(); i += count) {
            boolean relative = Character.isLowerCase(command);
            char upper = Character.toUpperCase(command);
            float x0 = s.x, y0 = s.y;
            switch (upper) {
                case 'M': {
                    float x = v.get(i) + (relative ? x0 : 0), y = v.get(i + 1) + (relative ? y0 : 0);
                    if (i == 0) { path.moveTo(x, y); s.startX = x; s.startY = y; }
                    else path.lineTo(x, y);
                    s.x = x; s.y = y; s.controlX = x; s.controlY = y;
                    break;
                }
                case 'L': {
                    s.x = v.get(i) + (relative ? x0 : 0); s.y = v.get(i + 1) + (relative ? y0 : 0);
                    path.lineTo(s.x, s.y); s.controlX = s.x; s.controlY = s.y;
                    break;
                }
                case 'H':
                    s.x = v.get(i) + (relative ? x0 : 0); path.lineTo(s.x, s.y); s.controlX = s.x; s.controlY = s.y; break;
                case 'V':
                    s.y = v.get(i) + (relative ? y0 : 0); path.lineTo(s.x, s.y); s.controlX = s.x; s.controlY = s.y; break;
                case 'C': {
                    float x1 = v.get(i) + (relative ? x0 : 0), y1 = v.get(i + 1) + (relative ? y0 : 0);
                    float x2 = v.get(i + 2) + (relative ? x0 : 0), y2 = v.get(i + 3) + (relative ? y0 : 0);
                    s.x = v.get(i + 4) + (relative ? x0 : 0); s.y = v.get(i + 5) + (relative ? y0 : 0);
                    path.cubicTo(x1, y1, x2, y2, s.x, s.y); s.controlX = x2; s.controlY = y2;
                    break;
                }
                case 'S': {
                    boolean reflect = "CcSs".indexOf(s.previous) >= 0;
                    float x1 = reflect ? 2 * x0 - s.controlX : x0, y1 = reflect ? 2 * y0 - s.controlY : y0;
                    float x2 = v.get(i) + (relative ? x0 : 0), y2 = v.get(i + 1) + (relative ? y0 : 0);
                    s.x = v.get(i + 2) + (relative ? x0 : 0); s.y = v.get(i + 3) + (relative ? y0 : 0);
                    path.cubicTo(x1, y1, x2, y2, s.x, s.y); s.controlX = x2; s.controlY = y2;
                    break;
                }
                case 'Q': {
                    float x1 = v.get(i) + (relative ? x0 : 0), y1 = v.get(i + 1) + (relative ? y0 : 0);
                    s.x = v.get(i + 2) + (relative ? x0 : 0); s.y = v.get(i + 3) + (relative ? y0 : 0);
                    path.quadTo(x1, y1, s.x, s.y); s.controlX = x1; s.controlY = y1;
                    break;
                }
                case 'T': {
                    boolean reflect = "QqTt".indexOf(s.previous) >= 0;
                    float x1 = reflect ? 2 * x0 - s.controlX : x0, y1 = reflect ? 2 * y0 - s.controlY : y0;
                    s.x = v.get(i) + (relative ? x0 : 0); s.y = v.get(i + 1) + (relative ? y0 : 0);
                    path.quadTo(x1, y1, s.x, s.y); s.controlX = x1; s.controlY = y1;
                    break;
                }
                case 'A': {
                    float rx = Math.abs(v.get(i)), ry = Math.abs(v.get(i + 1)), angle = v.get(i + 2);
                    boolean large = v.get(i + 3) != 0, sweep = v.get(i + 4) != 0;
                    s.x = v.get(i + 5) + (relative ? x0 : 0); s.y = v.get(i + 6) + (relative ? y0 : 0);
                    drawArc(path, x0, y0, s.x, s.y, rx, ry, angle, large, sweep);
                    s.controlX = s.x; s.controlY = s.y;
                    break;
                }
                default: throw new IllegalArgumentException("Unknown path command");
            }
            s.previous = command;
        }
    }

    private static int parameterCount(char command) {
        switch (Character.toUpperCase(command)) {
            case 'Z': return 0;
            case 'H': case 'V': return 1;
            case 'M': case 'L': case 'T': return 2;
            case 'S': case 'Q': return 4;
            case 'C': return 6;
            case 'A': return 7;
            default: throw new IllegalArgumentException("Unknown path command");
        }
    }

    private static void drawArc(Path path, double x0, double y0, double x1, double y1,
                                double a, double b, double theta, boolean large, boolean sweep) {
        if (a == 0 || b == 0) { path.lineTo((float) x1, (float) y1); return; }
        double radians = Math.toRadians(theta % 360.0), cos = Math.cos(radians), sin = Math.sin(radians);
        double x0p = (x0 * cos + y0 * sin) / a, y0p = (-x0 * sin + y0 * cos) / b;
        double x1p = (x1 * cos + y1 * sin) / a, y1p = (-x1 * sin + y1 * cos) / b;
        double dx = x0p - x1p, dy = y0p - y1p, distance = dx * dx + dy * dy;
        if (distance == 0) return;
        double discriminant = 1.0 / distance - 0.25;
        if (discriminant < 0) {
            double adjust = Math.sqrt(distance) / 1.99999;
            drawArc(path, x0, y0, x1, y1, a * adjust, b * adjust, theta, large, sweep);
            return;
        }
        double factor = Math.sqrt(discriminant), cx, cy;
        if (large == sweep) { cx = (x0p + x1p) / 2 - factor * dy; cy = (y0p + y1p) / 2 + factor * dx; }
        else { cx = (x0p + x1p) / 2 + factor * dy; cy = (y0p + y1p) / 2 - factor * dx; }
        double start = Math.atan2(y0p - cy, x0p - cx), extent = Math.atan2(y1p - cy, x1p - cx) - start;
        if (sweep != (extent >= 0)) extent += extent > 0 ? -2 * Math.PI : 2 * Math.PI;
        double centerX = cx * a * cos - cy * b * sin;
        double centerY = cx * a * sin + cy * b * cos;
        arcToBezier(path, centerX, centerY, a, b, radians, start, extent);
    }

    private static void arcToBezier(Path path, double cx, double cy, double a, double b,
                                    double theta, double start, double extent) {
        int segments = Math.max(1, (int) Math.ceil(Math.abs(extent * 4 / Math.PI)));
        double angle = extent / segments, cosTheta = Math.cos(theta), sinTheta = Math.sin(theta);
        double eta = start;
        double x = cx + a * cosTheta * Math.cos(eta) - b * sinTheta * Math.sin(eta);
        double y = cy + a * sinTheta * Math.cos(eta) + b * cosTheta * Math.sin(eta);
        double dx = -a * cosTheta * Math.sin(eta) - b * sinTheta * Math.cos(eta);
        double dy = -a * sinTheta * Math.sin(eta) + b * cosTheta * Math.cos(eta);
        for (int i = 0; i < segments; i++) {
            double next = eta + angle;
            double nx = cx + a * cosTheta * Math.cos(next) - b * sinTheta * Math.sin(next);
            double ny = cy + a * sinTheta * Math.cos(next) + b * cosTheta * Math.sin(next);
            double ndx = -a * cosTheta * Math.sin(next) - b * sinTheta * Math.cos(next);
            double ndy = -a * sinTheta * Math.sin(next) + b * cosTheta * Math.cos(next);
            double tan = Math.tan((next - eta) / 2);
            double alpha = Math.sin(next - eta) * (Math.sqrt(4 + 3 * tan * tan) - 1) / 3;
            path.cubicTo((float) (x + alpha * dx), (float) (y + alpha * dy),
                    (float) (nx - alpha * ndx), (float) (ny - alpha * ndy), (float) nx, (float) ny);
            eta = next; x = nx; y = ny; dx = ndx; dy = ndy;
        }
    }

    private static final class State {
        float x, y, controlX, controlY, startX, startY;
        char previous;
    }
}
