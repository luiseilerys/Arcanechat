package com.amulyakhare.textdrawable;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;

/**
 * Minimal, dependency-free replacement for {@code com.github.amulyakhare:TextDrawable}.
 *
 * <p>The upstream artifact was published on JitPack from a commit hash
 * ({@code 558677ea31}) that can no longer be resolved, which made every clean build fail. Only the
 * features this app uses are implemented: a coloured circle/rectangle with centred text (unread
 * counters, connectivity symbols and the one letter avatar of contacts without a picture).
 */
public class TextDrawable extends Drawable {

  /** Builder API kept source-compatible with the original library. */
  public interface IShapeBuilder {
    IShapeBuilder beginConfig();

    IShapeBuilder endConfig();

    IShapeBuilder width(int width);

    IShapeBuilder height(int height);

    IShapeBuilder textColor(int color);

    IShapeBuilder fontSize(int size);

    IShapeBuilder bold();

    IShapeBuilder toUpperCase();

    TextDrawable buildRound(String text, int color);

    TextDrawable buildRect(String text, int color);

    TextDrawable buildRoundRect(String text, int color, int radius);
  }

  private static final float TEXT_WIDTH_RATIO = 0.8f;
  private static final float DEFAULT_TEXT_RATIO = 0.45f;

  private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF shapeRect = new RectF();

  private final @Nullable String text;
  private final int shapeColor;
  private final int textColor;
  private final boolean round;
  private final boolean bold;
  private final int fontSize;
  private final int radius;
  private final int intrinsicWidth;
  private final int intrinsicHeight;

  private int alpha = 255;
  private @Nullable ColorFilter colorFilter;

  private TextDrawable(Builder builder) {
    String value = builder.text == null ? "" : builder.text;
    this.text = builder.toUpperCase ? value.toUpperCase(Locale.getDefault()) : value;
    this.shapeColor = builder.shapeColor;
    this.textColor = builder.textColor;
    this.round = builder.round;
    this.bold = builder.bold;
    this.fontSize = builder.fontSize;
    this.radius = builder.radius;
    this.intrinsicWidth = builder.width;
    this.intrinsicHeight = builder.height;
    this.shapePaint.setColor(shapeColor);
    this.textPaint.setColor(textColor);
    this.textPaint.setTextAlign(Paint.Align.CENTER);
  }

  public static IShapeBuilder builder() {
    return new Builder();
  }

  @Override
  public void draw(@NonNull Canvas canvas) {
    Rect bounds = getBounds();
    if (bounds.isEmpty()) return;

    float width = bounds.width();
    float height = bounds.height();

    shapePaint.setColor(shapeColor);
    shapePaint.setAlpha(alpha);
    shapePaint.setColorFilter(colorFilter);

    if (round) {
      canvas.drawCircle(
          bounds.exactCenterX(), bounds.exactCenterY(), Math.min(width, height) / 2f, shapePaint);
    } else if (radius > 0) {
      shapeRect.set(bounds);
      float corner = Math.min(radius, Math.min(width, height) / 2f);
      canvas.drawRoundRect(shapeRect, corner, corner, shapePaint);
    } else {
      canvas.drawRect(bounds, shapePaint);
    }

    if (text == null || text.trim().isEmpty()) return;

    textPaint.setColor(textColor);
    textPaint.setAlpha(alpha);
    textPaint.setColorFilter(colorFilter);
    textPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    textPaint.setTextSize(fontSize > 0 ? fontSize : Math.min(width, height) * DEFAULT_TEXT_RATIO);

    float maxWidth = width * TEXT_WIDTH_RATIO;
    float measured = textPaint.measureText(text);
    if (measured > maxWidth && measured > 0) {
      textPaint.setTextSize(textPaint.getTextSize() * maxWidth / measured);
    }

    Paint.FontMetrics metrics = textPaint.getFontMetrics();
    float baseline = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f;
    canvas.drawText(text, bounds.exactCenterX(), baseline, textPaint);
  }

  @Override
  public void setAlpha(int alpha) {
    this.alpha = alpha;
    invalidateSelf();
  }

  @Override
  public void setColorFilter(@Nullable ColorFilter colorFilter) {
    this.colorFilter = colorFilter;
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  @Override
  public int getIntrinsicWidth() {
    return intrinsicWidth > 0 ? intrinsicWidth : -1;
  }

  @Override
  public int getIntrinsicHeight() {
    return intrinsicHeight > 0 ? intrinsicHeight : -1;
  }

  /** Mutable builder; the original library also mutated a shared instance. */
  private static final class Builder implements IShapeBuilder {

    private String text;
    private int shapeColor = 0;
    private int textColor = 0xFFFFFFFF;
    private boolean round = true;
    private boolean bold = false;
    private boolean toUpperCase = false;
    private int fontSize = 0;
    private int width = 0;
    private int height = 0;
    private int radius = 0;

    @Override
    public IShapeBuilder beginConfig() {
      return this;
    }

    @Override
    public IShapeBuilder endConfig() {
      return this;
    }

    @Override
    public IShapeBuilder width(int width) {
      this.width = width;
      return this;
    }

    @Override
    public IShapeBuilder height(int height) {
      this.height = height;
      return this;
    }

    @Override
    public IShapeBuilder textColor(int color) {
      this.textColor = color;
      return this;
    }

    @Override
    public IShapeBuilder fontSize(int size) {
      this.fontSize = size;
      return this;
    }

    @Override
    public IShapeBuilder bold() {
      this.bold = true;
      return this;
    }

    @Override
    public IShapeBuilder toUpperCase() {
      this.toUpperCase = true;
      return this;
    }

    @Override
    public TextDrawable buildRound(String text, int color) {
      return build(text, color, true, 0);
    }

    @Override
    public TextDrawable buildRect(String text, int color) {
      return build(text, color, false, 0);
    }

    @Override
    public TextDrawable buildRoundRect(String text, int color, int radius) {
      return build(text, color, false, radius);
    }

    private TextDrawable build(String text, int color, boolean round, int radius) {
      this.text = text;
      this.shapeColor = color;
      this.round = round;
      this.radius = radius;
      return new TextDrawable(this);
    }
  }
}
