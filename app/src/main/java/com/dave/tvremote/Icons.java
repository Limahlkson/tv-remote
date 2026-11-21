package com.dave.tvremote;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Simple hand-drawn icons so the app needs no image resources. */
public final class Icons {
    public static final int NONE = 0, POWER = 1, MENU = 2, GEAR = 3, INPUT = 4, MUTE = 5, KEYBOARD = 6,
            BACK = 7, HOME = 8, MOON = 9, MORE = 10, PLAY_PAUSE = 11, REWIND = 12, FFWD = 13, TV = 14,
            CHEV_UP = 15, CHEV_DOWN = 16, PLUS = 17, MINUS = 18, EXIT = 19, MIC = 20;

    private static final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    private Icons() {
    }

    /** Draws icon centred on (cx, cy); u is roughly half the icon's width. */
    public static void draw(Canvas c, int icon, float cx, float cy, float u, int color) {
        stroke.setColor(color);
        fill.setColor(color);
        stroke.setStrokeWidth(Math.max(2f, u * 0.2f));
        Path p = new Path();
        switch (icon) {
            case POWER:
                c.drawArc(new RectF(cx - u, cy - u * 0.9f, cx + u, cy + u * 1.1f), -60, 300, false, stroke);
                c.drawLine(cx, cy - u * 1.15f, cx, cy - u * 0.05f, stroke);
                break;
            case MENU:
                for (int i = -1; i <= 1; i++) c.drawLine(cx - u, cy + i * u * 0.6f, cx + u, cy + i * u * 0.6f, stroke);
                break;
            case GEAR: {
                Path gear = new Path();
                gear.addCircle(cx, cy, u * 0.72f, Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    Path tooth = new Path();
                    tooth.addRect(cx - u * 0.17f, cy - u * 1.05f, cx + u * 0.17f, cy - u * 0.5f, Path.Direction.CW);
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.setRotate(i * 45f, cx, cy);
                    tooth.transform(m);
                    gear.op(tooth, Path.Op.UNION);
                }
                Path hole = new Path();
                hole.addCircle(cx, cy, u * 0.3f, Path.Direction.CW);
                gear.op(hole, Path.Op.DIFFERENCE);
                c.drawPath(gear, fill);
                break;
            }
            case INPUT:
                c.drawRoundRect(new RectF(cx - u * 0.25f, cy - u * 0.8f, cx + u, cy + u * 0.8f), u * 0.15f, u * 0.15f, stroke);
                c.drawLine(cx - u * 1.1f, cy, cx + u * 0.45f, cy, stroke);
                c.drawLine(cx + u * 0.45f, cy, cx + u * 0.08f, cy - u * 0.37f, stroke);
                c.drawLine(cx + u * 0.45f, cy, cx + u * 0.08f, cy + u * 0.37f, stroke);
                break;
            case MUTE:
                p.moveTo(cx - u, cy - u * 0.35f);
                p.lineTo(cx - u * 0.55f, cy - u * 0.35f);
                p.lineTo(cx - u * 0.05f, cy - u * 0.85f);
                p.lineTo(cx - u * 0.05f, cy + u * 0.85f);
                p.lineTo(cx - u * 0.55f, cy + u * 0.35f);
                p.lineTo(cx - u, cy + u * 0.35f);
                p.close();
                c.drawPath(p, fill);
                c.drawLine(cx + u * 0.3f, cy - u * 0.38f, cx + u * 1.05f, cy + u * 0.38f, stroke);
                c.drawLine(cx + u * 0.3f, cy + u * 0.38f, cx + u * 1.05f, cy - u * 0.38f, stroke);
                break;
            case KEYBOARD: {
                Paint sp = new Paint(stroke);
                sp.setStrokeWidth(Math.max(2f, u * 0.13f));
                c.drawRoundRect(new RectF(cx - u * 1.15f, cy - u * 0.72f, cx + u * 1.15f, cy + u * 0.72f), u * 0.18f, u * 0.18f, sp);
                float d = u * 0.11f;
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 5; col++) {
                        float x = cx - u * 0.72f + col * u * 0.36f;
                        float y = cy - u * 0.33f + row * u * 0.33f;
                        c.drawRect(x - d, y - d, x + d, y + d, fill);
                    }
                }
                c.drawRect(cx - u * 0.5f, cy + u * 0.28f, cx + u * 0.5f, cy + u * 0.42f, fill);
                break;
            }
            case BACK:
                p.moveTo(cx - u * 0.9f, cy - u * 0.35f);
                p.lineTo(cx + u * 0.3f, cy - u * 0.35f);
                p.arcTo(new RectF(cx - u * 0.3f, cy - u * 0.35f, cx + u * 0.9f, cy + u * 0.85f), -90, 180, false);
                p.lineTo(cx - u * 0.55f, cy + u * 0.85f);
                c.drawPath(p, stroke);
                c.drawLine(cx - u * 0.9f, cy - u * 0.35f, cx - u * 0.48f, cy - u * 0.77f, stroke);
                c.drawLine(cx - u * 0.9f, cy - u * 0.35f, cx - u * 0.48f, cy + u * 0.07f, stroke);
                break;
            case HOME:
                p.moveTo(cx - u * 1.05f, cy - u * 0.05f);
                p.lineTo(cx, cy - u * 0.95f);
                p.lineTo(cx + u * 1.05f, cy - u * 0.05f);
                p.moveTo(cx - u * 0.75f, cy - u * 0.3f);
                p.lineTo(cx - u * 0.75f, cy + u * 0.85f);
                p.lineTo(cx + u * 0.75f, cy + u * 0.85f);
                p.lineTo(cx + u * 0.75f, cy - u * 0.3f);
                c.drawPath(p, stroke);
                break;
            case MOON: {
                Path a = new Path();
                a.addCircle(cx, cy, u, Path.Direction.CW);
                Path b = new Path();
                b.addCircle(cx + u * 0.6f, cy - u * 0.45f, u * 0.85f, Path.Direction.CW);
                a.op(b, Path.Op.DIFFERENCE);
                c.drawPath(a, fill);
                break;
            }
            case MORE:
                for (int i = -1; i <= 1; i++) c.drawCircle(cx, cy + i * u * 0.7f, u * 0.17f, fill);
                break;
            case PLAY_PAUSE:
                p.moveTo(cx - u * 1.0f, cy - u * 0.7f);
                p.lineTo(cx - u * 1.0f, cy + u * 0.7f);
                p.lineTo(cx - u * 0.05f, cy);
                p.close();
                c.drawPath(p, fill);
                c.drawRect(cx + u * 0.2f, cy - u * 0.7f, cx + u * 0.45f, cy + u * 0.7f, fill);
                c.drawRect(cx + u * 0.7f, cy - u * 0.7f, cx + u * 0.95f, cy + u * 0.7f, fill);
                break;
            case REWIND:
            case FFWD: {
                float dir = icon == FFWD ? 1 : -1;
                for (int k = 0; k < 2; k++) {
                    float base = cx + dir * (k == 0 ? -1.0f : 0f) * u;
                    float tip = base + dir * u;
                    p.moveTo(base, cy - u * 0.65f);
                    p.lineTo(base, cy + u * 0.65f);
                    p.lineTo(tip, cy);
                    p.close();
                }
                c.drawPath(p, fill);
                break;
            }
            case TV:
                c.drawRoundRect(new RectF(cx - u, cy - u * 0.45f, cx + u, cy + u * 0.85f), u * 0.15f, u * 0.15f, stroke);
                c.drawLine(cx - u * 0.45f, cy - u * 1.0f, cx, cy - u * 0.45f, stroke);
                c.drawLine(cx + u * 0.45f, cy - u * 1.0f, cx, cy - u * 0.45f, stroke);
                break;
            case CHEV_UP:
                p.moveTo(cx - u * 0.7f, cy + u * 0.35f);
                p.lineTo(cx, cy - u * 0.35f);
                p.lineTo(cx + u * 0.7f, cy + u * 0.35f);
                c.drawPath(p, stroke);
                break;
            case CHEV_DOWN:
                p.moveTo(cx - u * 0.7f, cy - u * 0.35f);
                p.lineTo(cx, cy + u * 0.35f);
                p.lineTo(cx + u * 0.7f, cy - u * 0.35f);
                c.drawPath(p, stroke);
                break;
            case PLUS:
                c.drawLine(cx - u * 0.7f, cy, cx + u * 0.7f, cy, stroke);
                c.drawLine(cx, cy - u * 0.7f, cx, cy + u * 0.7f, stroke);
                break;
            case MINUS:
                c.drawLine(cx - u * 0.7f, cy, cx + u * 0.7f, cy, stroke);
                break;
            case MIC:
                c.drawRoundRect(new RectF(cx - u * 0.38f, cy - u * 1.0f, cx + u * 0.38f, cy + u * 0.25f), u * 0.38f, u * 0.38f, fill);
                c.drawArc(new RectF(cx - u * 0.72f, cy - u * 0.6f, cx + u * 0.72f, cy + u * 0.6f), 0, 180, false, stroke);
                c.drawLine(cx, cy + u * 0.6f, cx, cy + u * 1.0f, stroke);
                break;
            case EXIT:
                c.drawLine(cx - u * 0.6f, cy - u * 0.6f, cx + u * 0.6f, cy + u * 0.6f, stroke);
                c.drawLine(cx - u * 0.6f, cy + u * 0.6f, cx + u * 0.6f, cy - u * 0.6f, stroke);
                break;
            default:
                break;
        }
    }
}
