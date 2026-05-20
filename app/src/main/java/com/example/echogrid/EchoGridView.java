package com.example.echogrid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EchoGridView extends View {
    private static final int BASE_GRID = 5;
    private static final int MAX_GRID = 12;
    private static final int MAX_PROBES = 42;

    private static final int COLOR_INK = Color.rgb(5, 11, 17);
    private static final int COLOR_DEEP = Color.rgb(6, 24, 25);
    private static final int COLOR_PANEL = Color.rgb(13, 24, 31);
    private static final int COLOR_GRID = Color.rgb(65, 97, 107);
    private static final int COLOR_MUTED = Color.rgb(150, 170, 176);
    private static final int COLOR_TEXT = Color.rgb(232, 244, 240);
    private static final int COLOR_CYAN = Color.rgb(76, 221, 213);
    private static final int COLOR_GOLD = Color.rgb(255, 198, 84);
    private static final int COLOR_CORAL = Color.rgb(255, 93, 108);
    private static final int COLOR_VIOLET = Color.rgb(151, 111, 255);

    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF board = new RectF();
    private final RectF tempRect = new RectF();
    private final RectF hudPanel = new RectF();
    private final List<Probe> probes = new ArrayList<>();
    private final List<EchoPulse> pulses = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final AudioCuePlayer audioCuePlayer;
    private final Haptics haptics;

    private final float density;
    private final float scaledDensity;
    private final int touchSlop;

    private int gridSize = BASE_GRID;
    private int level = 1;
    private int targetX;
    private int targetY;
    private int score;
    private int streak;
    private int bestStreak;
    private int guessesThisRound;
    private int totalGuesses;
    private int lastAxisX;
    private int lastAxisY;
    private float lastCloseness;
    private float cellSize;
    private float touchDownX;
    private float touchDownY;
    private long roundStartMs;
    private long lastFrameMs;
    private boolean running;
    private boolean roundWon;
    private String cueText = "READY";

    public EchoGridView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        audioCuePlayer = new AudioCuePlayer();
        haptics = new Haptics(context);
        setFocusable(true);
        setKeepScreenOn(true);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        startRound(false);
    }

    public void resume() {
        running = true;
        lastFrameMs = 0L;
        postInvalidateOnAnimation();
    }

    public void pause() {
        running = false;
    }

    public void release() {
        audioCuePlayer.release();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float deltaSeconds = lastFrameMs == 0L ? 0.016f : Math.min(0.033f, (now - lastFrameMs) / 1000.0f);
        lastFrameMs = now;

        layoutBoard(getWidth(), getHeight());
        updateSparks(deltaSeconds);
        drawBackground(canvas);
        drawBoard(canvas, now);
        drawPulses(canvas, now);
        drawSparks(canvas);
        drawHud(canvas, now);

        if (running) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = Math.abs(event.getX() - touchDownX);
                float dy = Math.abs(event.getY() - touchDownY);
                if (dx <= touchSlop * 2.0f && dy <= touchSlop * 2.0f) {
                    handleTap(event.getX(), event.getY());
                    performClick();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void startRound(boolean advanced) {
        if (advanced) {
            level++;
        }
        gridSize = Math.min(MAX_GRID, BASE_GRID + (level - 1) / 2);
        targetX = random.nextInt(gridSize);
        targetY = random.nextInt(gridSize);
        guessesThisRound = 0;
        lastAxisX = gridSize - 1;
        lastAxisY = gridSize - 1;
        lastCloseness = 0.0f;
        roundWon = false;
        cueText = "LEVEL " + level;
        probes.clear();
        pulses.clear();
        sparks.clear();
        roundStartMs = SystemClock.uptimeMillis();
        addOpeningSweep();
        invalidate();
    }

    private void handleTap(float x, float y) {
        if (roundWon) {
            startRound(true);
            return;
        }
        if (!board.contains(x, y) || cellSize <= 0.0f) {
            return;
        }

        int gridX = clamp((int) ((x - board.left) / cellSize), 0, gridSize - 1);
        int gridY = clamp((int) ((y - board.top) / cellSize), 0, gridSize - 1);
        int axisX = Math.abs(gridX - targetX);
        int axisY = Math.abs(gridY - targetY);
        float distance = (float) Math.hypot(axisX, axisY);
        float maxDistance = (float) Math.hypot(gridSize - 1, gridSize - 1);
        float closeness = 1.0f - Math.min(1.0f, distance / Math.max(1.0f, maxDistance));
        boolean hit = axisX == 0 && axisY == 0;

        guessesThisRound++;
        totalGuesses++;
        lastAxisX = axisX;
        lastAxisY = axisY;
        lastCloseness = closeness;
        cueText = buildCueText(closeness, hit);

        float centerX = board.left + gridX * cellSize + cellSize * 0.5f;
        float centerY = board.top + gridY * cellSize + cellSize * 0.5f;
        probes.add(new Probe(gridX, gridY, closeness, hit));
        while (probes.size() > MAX_PROBES) {
            probes.remove(0);
        }

        int pulseColor = hit ? COLOR_GOLD : blendColor(COLOR_CYAN, COLOR_CORAL, closeness);
        pulses.add(new EchoPulse(centerX, centerY, cellSize * (2.2f + closeness * 4.0f), pulseColor, hit ? 1050L : 820L));
        if (closeness > 0.62f && !hit) {
            pulses.add(new EchoPulse(
                    board.left + targetX * cellSize + cellSize * 0.5f,
                    board.top + targetY * cellSize + cellSize * 0.5f,
                    cellSize * 1.4f,
                    COLOR_VIOLET,
                    520L));
        }

        audioCuePlayer.playScan(axisX, axisY, gridSize - 1, closeness, hit);
        haptics.pulse(closeness, hit);

        if (hit) {
            onTargetFound(centerX, centerY);
        }
    }

    private void onTargetFound(float centerX, float centerY) {
        roundWon = true;
        long elapsedSeconds = Math.max(1L, (SystemClock.uptimeMillis() - roundStartMs) / 1000L);
        int par = Math.max(3, gridSize / 2 + 1);
        int efficiencyBonus = Math.max(0, (par + 2 - guessesThisRound) * 35);
        int timeBonus = Math.max(0, 120 - (int) elapsedSeconds * 4);
        int levelBonus = 85 + level * 55;
        int roundScore = levelBonus + efficiencyBonus + timeBonus + Math.max(0, streak) * 25;
        score += roundScore;

        if (guessesThisRound <= par) {
            streak++;
        } else {
            streak = 0;
        }
        bestStreak = Math.max(bestStreak, streak);
        cueText = String.format(Locale.US, "+%d CORE FOUND", roundScore);

        for (int i = 0; i < 54; i++) {
            sparks.add(Spark.burst(random, centerX, centerY, COLOR_GOLD, COLOR_CYAN, COLOR_CORAL));
        }
        pulses.add(new EchoPulse(centerX, centerY, board.width() * 0.85f, COLOR_GOLD, 1300L));
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (roundWon) {
                    startRound(true);
                }
            }
        }, 1350L);
    }

    private String buildCueText(float closeness, boolean hit) {
        if (hit) {
            return "CORE FOUND";
        }
        if (closeness >= 0.82f) {
            return "NEAR LOCK";
        }
        if (closeness >= 0.64f) {
            return "STRONG ECHO";
        }
        if (closeness >= 0.46f) {
            return "WARM ECHO";
        }
        if (closeness >= 0.27f) {
            return "FAINT ECHO";
        }
        return "COLD SIGNAL";
    }

    private void layoutBoard(int width, int height) {
        float margin = dp(18.0f);
        if (width >= height) {
            float boardSize = Math.min(height - margin * 2.0f, width * 0.63f);
            boardSize = Math.max(dp(220.0f), boardSize);
            board.set(margin, (height - boardSize) * 0.5f, margin + boardSize, (height + boardSize) * 0.5f);
        } else {
            float boardSize = Math.min(width - margin * 2.0f, height * 0.62f);
            boardSize = Math.max(dp(220.0f), boardSize);
            board.set((width - boardSize) * 0.5f, margin, (width + boardSize) * 0.5f, margin + boardSize);
        }
        cellSize = board.width() / Math.max(1, gridSize);
    }

    private void drawBackground(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                0.0f,
                0.0f,
                getWidth(),
                getHeight(),
                new int[]{COLOR_INK, COLOR_DEEP, Color.rgb(22, 15, 32)},
                new float[]{0.0f, 0.62f, 1.0f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.0f));
        for (int i = 0; i < 7; i++) {
            float inset = dp(24.0f + i * 38.0f);
            paint.setColor(withAlpha(i % 2 == 0 ? COLOR_CYAN : COLOR_VIOLET, 18 - i));
            canvas.drawOval(-inset, -inset * 0.4f, getWidth() + inset, getHeight() + inset * 0.8f, paint);
        }
    }

    private void drawBoard(Canvas canvas, long now) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(COLOR_PANEL, 225));
        canvas.drawRoundRect(board, dp(12.0f), dp(12.0f), paint);

        for (int y = 0; y < gridSize; y++) {
            for (int x = 0; x < gridSize; x++) {
                float heat = heatForCell(x, y);
                if (heat <= 0.01f) {
                    continue;
                }
                int heatColor = blendColor(COLOR_CYAN, COLOR_GOLD, Math.min(1.0f, heat));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(heatColor, 28 + (int) (heat * 115.0f)));
                tempRect.set(
                        board.left + x * cellSize + dp(1.0f),
                        board.top + y * cellSize + dp(1.0f),
                        board.left + (x + 1) * cellSize - dp(1.0f),
                        board.top + (y + 1) * cellSize - dp(1.0f));
                canvas.drawRoundRect(tempRect, dp(5.0f), dp(5.0f), paint);
            }
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.0f));
        paint.setColor(withAlpha(COLOR_GRID, 165));
        for (int i = 0; i <= gridSize; i++) {
            float line = board.left + i * cellSize;
            canvas.drawLine(line, board.top, line, board.bottom, paint);
            line = board.top + i * cellSize;
            canvas.drawLine(board.left, line, board.right, line, paint);
        }

        drawProbeMarks(canvas);
        if (roundWon) {
            drawTarget(canvas, now);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.0f));
        paint.setColor(withAlpha(COLOR_CYAN, 170));
        canvas.drawRoundRect(board, dp(12.0f), dp(12.0f), paint);
    }

    private void drawProbeMarks(Canvas canvas) {
        for (Probe probe : probes) {
            float cx = board.left + probe.gridX * cellSize + cellSize * 0.5f;
            float cy = board.top + probe.gridY * cellSize + cellSize * 0.5f;
            float radius = cellSize * (0.13f + probe.closeness * 0.16f);
            int color = probe.hit ? COLOR_GOLD : blendColor(COLOR_CYAN, COLOR_CORAL, probe.closeness);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(color, 185));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(withAlpha(color, 220));
            canvas.drawCircle(cx, cy, radius + dp(3.0f), paint);
        }
    }

    private void drawTarget(Canvas canvas, long now) {
        float cx = board.left + targetX * cellSize + cellSize * 0.5f;
        float cy = board.top + targetY * cellSize + cellSize * 0.5f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 120.0f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(COLOR_GOLD, 88));
        canvas.drawCircle(cx, cy, cellSize * (0.45f + pulse * 0.12f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3.0f));
        paint.setColor(COLOR_GOLD);
        canvas.drawCircle(cx, cy, cellSize * 0.34f, paint);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(COLOR_TEXT);
        canvas.drawCircle(cx, cy, cellSize * 0.16f, paint);
    }

    private void drawPulses(Canvas canvas, long now) {
        paint.setStyle(Paint.Style.STROKE);
        for (int i = pulses.size() - 1; i >= 0; i--) {
            EchoPulse pulse = pulses.get(i);
            float t = pulse.progress(now);
            if (t >= 1.0f) {
                pulses.remove(i);
                continue;
            }
            float eased = 1.0f - (1.0f - t) * (1.0f - t);
            paint.setStrokeWidth(dp(2.0f + 4.0f * (1.0f - t)));
            paint.setColor(withAlpha(pulse.color, (int) (180.0f * (1.0f - t))));
            canvas.drawCircle(pulse.x, pulse.y, pulse.maxRadius * eased, paint);
        }
    }

    private void drawSparks(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        for (Spark spark : sparks) {
            paint.setColor(withAlpha(spark.color, (int) (255.0f * spark.life)));
            canvas.drawCircle(spark.x, spark.y, dp(1.8f + 2.8f * spark.life), paint);
        }
    }

    private void drawHud(Canvas canvas, long now) {
        float margin = dp(18.0f);
        float hudLeft = board.right + margin;
        float hudWidth = getWidth() - hudLeft - margin;
        boolean sideHud = hudWidth >= dp(190.0f);
        if (!sideHud) {
            hudLeft = margin;
            hudWidth = getWidth() - margin * 2.0f;
            hudPanel.set(hudLeft, board.bottom + dp(10.0f), hudLeft + hudWidth, getHeight() - margin);
            if (hudPanel.height() < dp(96.0f)) {
                hudPanel.set(hudLeft, margin, hudLeft + Math.min(hudWidth, dp(280.0f)), margin + dp(120.0f));
            }
        } else {
            hudPanel.set(hudLeft, board.top, hudLeft + hudWidth, board.bottom);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.rgb(8, 15, 20), 210));
        canvas.drawRoundRect(hudPanel, dp(12.0f), dp(12.0f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.0f));
        paint.setColor(withAlpha(COLOR_CYAN, 90));
        canvas.drawRoundRect(hudPanel, dp(12.0f), dp(12.0f), paint);

        float x = hudPanel.left + dp(16.0f);
        float y = hudPanel.top + dp(30.0f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(COLOR_TEXT);
        textPaint.setTextSize(sp(26.0f));
        canvas.drawText("EchoGrid", x, y, textPaint);

        textPaint.setTextSize(sp(12.0f));
        textPaint.setColor(COLOR_MUTED);
        canvas.drawText(String.format(Locale.US, "LEVEL %d  GRID %dx%d", level, gridSize, gridSize), x, y + dp(22.0f), textPaint);

        y += dp(60.0f);
        drawMetric(canvas, "SCORE", String.valueOf(score), x, y);
        y += dp(45.0f);
        drawMetric(canvas, "GUESSES", String.format(Locale.US, "%d / %d", guessesThisRound, totalGuesses), x, y);
        y += dp(45.0f);
        drawMetric(canvas, "STREAK", String.format(Locale.US, "%d  BEST %d", streak, bestStreak), x, y);

        y += dp(50.0f);
        textPaint.setTextSize(sp(13.0f));
        textPaint.setColor(COLOR_MUTED);
        canvas.drawText("RESONANCE", x, y, textPaint);
        y += dp(25.0f);
        textPaint.setTextSize(sp(20.0f));
        textPaint.setColor(roundWon ? COLOR_GOLD : blendColor(COLOR_CYAN, COLOR_CORAL, lastCloseness));
        canvas.drawText(cueText, x, y, textPaint);

        y += dp(30.0f);
        drawAxisBar(canvas, "X", 1.0f - lastAxisX / Math.max(1.0f, gridSize - 1.0f), x, y, hudPanel.right - x - dp(16.0f));
        y += dp(28.0f);
        drawAxisBar(canvas, "Y", 1.0f - lastAxisY / Math.max(1.0f, gridSize - 1.0f), x, y, hudPanel.right - x - dp(16.0f));

        textPaint.setTextSize(sp(12.0f));
        if (roundWon) {
            textPaint.setColor(withAlpha(COLOR_GOLD, 220));
            canvas.drawText("ADVANCING", x, hudPanel.bottom - dp(22.0f), textPaint);
        } else {
            float elapsed = (now - roundStartMs) / 1000.0f;
            textPaint.setColor(withAlpha(COLOR_MUTED, 210));
            canvas.drawText(String.format(Locale.US, "TIME %.0fs", elapsed), x, hudPanel.bottom - dp(22.0f), textPaint);
        }
    }

    private void drawMetric(Canvas canvas, String label, String value, float x, float y) {
        textPaint.setTextSize(sp(11.0f));
        textPaint.setColor(COLOR_MUTED);
        canvas.drawText(label, x, y, textPaint);
        textPaint.setTextSize(sp(23.0f));
        textPaint.setColor(COLOR_TEXT);
        canvas.drawText(value, x, y + dp(24.0f), textPaint);
    }

    private void drawAxisBar(Canvas canvas, String label, float value, float x, float y, float width) {
        value = clamp(value, 0.0f, 1.0f);
        textPaint.setTextSize(sp(12.0f));
        textPaint.setColor(COLOR_MUTED);
        canvas.drawText(label, x, y + dp(10.0f), textPaint);
        float barLeft = x + dp(24.0f);
        float barTop = y;
        float barHeight = dp(10.0f);
        tempRect.set(barLeft, barTop, barLeft + Math.max(dp(40.0f), width - dp(24.0f)), barTop + barHeight);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(COLOR_GRID, 130));
        canvas.drawRoundRect(tempRect, barHeight * 0.5f, barHeight * 0.5f, paint);
        tempRect.right = tempRect.left + tempRect.width() * value;
        paint.setColor(blendColor(COLOR_CYAN, COLOR_GOLD, value));
        canvas.drawRoundRect(tempRect, barHeight * 0.5f, barHeight * 0.5f, paint);
    }

    private void updateSparks(float deltaSeconds) {
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark spark = sparks.get(i);
            spark.update(deltaSeconds);
            if (spark.life <= 0.0f) {
                sparks.remove(i);
            }
        }
    }

    private float heatForCell(int x, int y) {
        float heat = 0.0f;
        for (Probe probe : probes) {
            float probeDistance = (float) Math.hypot(x - probe.gridX, y - probe.gridY);
            float localHeat = probe.closeness - probeDistance * 0.11f;
            heat = Math.max(heat, localHeat);
        }
        if (roundWon) {
            float targetDistance = (float) Math.hypot(x - targetX, y - targetY);
            heat = Math.max(heat, 1.0f - targetDistance * 0.22f);
        }
        return clamp(heat, 0.0f, 1.0f);
    }

    private void addOpeningSweep() {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cellSize > 0.0f && !roundWon) {
                    pulses.add(new EchoPulse(board.centerX(), board.centerY(), board.width() * 0.62f, COLOR_CYAN, 1200L));
                }
            }
        }, 80L);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * scaledDensity;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(clamp(alpha, 0, 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int blendColor(int from, int to, float amount) {
        float t = clamp(amount, 0.0f, 1.0f);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.rgb(r, g, b);
    }

    private static final class Probe {
        final int gridX;
        final int gridY;
        final float closeness;
        final boolean hit;

        Probe(int gridX, int gridY, float closeness, boolean hit) {
            this.gridX = gridX;
            this.gridY = gridY;
            this.closeness = closeness;
            this.hit = hit;
        }
    }

    private static final class EchoPulse {
        final float x;
        final float y;
        final float maxRadius;
        final int color;
        final long createdAt;
        final long durationMs;

        EchoPulse(float x, float y, float maxRadius, int color, long durationMs) {
            this.x = x;
            this.y = y;
            this.maxRadius = maxRadius;
            this.color = color;
            this.durationMs = durationMs;
            this.createdAt = SystemClock.uptimeMillis();
        }

        float progress(long now) {
            return (now - createdAt) / (float) durationMs;
        }
    }

    private static final class Spark {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        int color;

        static Spark burst(Random random, float x, float y, int first, int second, int third) {
            Spark spark = new Spark();
            float angle = (float) (random.nextFloat() * Math.PI * 2.0);
            float speed = 90.0f + random.nextFloat() * 420.0f;
            spark.x = x;
            spark.y = y;
            spark.vx = (float) Math.cos(angle) * speed;
            spark.vy = (float) Math.sin(angle) * speed;
            spark.life = 0.55f + random.nextFloat() * 0.55f;
            int pick = random.nextInt(3);
            spark.color = pick == 0 ? first : (pick == 1 ? second : third);
            return spark;
        }

        void update(float deltaSeconds) {
            x += vx * deltaSeconds;
            y += vy * deltaSeconds;
            vx *= 0.985f;
            vy *= 0.985f;
            vy += 140.0f * deltaSeconds;
            life -= deltaSeconds * 0.9f;
        }
    }

    private static final class Haptics {
        private final Vibrator vibrator;

        Haptics(Context context) {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }

        @SuppressWarnings("deprecation")
        void pulse(float closeness, boolean hit) {
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            if (hit) {
                vibrateWaveform();
                return;
            }
            int duration = 18 + (int) (42.0f * closeness);
            int amplitude = 35 + (int) (190.0f * closeness);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, clamp(amplitude, 1, 255)));
            } else {
                vibrator.vibrate(duration);
            }
        }

        @SuppressWarnings("deprecation")
        private void vibrateWaveform() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                long[] timings = new long[]{0L, 35L, 45L, 75L, 45L, 130L};
                int[] amplitudes = new int[]{0, 140, 0, 210, 0, 255};
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
            } else {
                vibrator.vibrate(new long[]{0L, 35L, 45L, 75L, 45L, 130L}, -1);
            }
        }
    }

    private static final class AudioCuePlayer {
        private static final int SAMPLE_RATE = 44100;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        void playScan(final int axisX, final int axisY, final int maxAxis, final float closeness, final boolean hit) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (hit) {
                        playChord();
                        return;
                    }
                    float xPitch = axisPitch(axisX, maxAxis);
                    float yPitch = axisPitch(axisY, maxAxis);
                    int gapMs = 45 + (int) ((1.0f - closeness) * 260.0f);
                    playTone(xPitch, 70, 0.42f);
                    SystemClock.sleep(gapMs);
                    playTone(yPitch, 82, 0.46f);
                }
            });
        }

        void release() {
            executor.shutdownNow();
        }

        private static float axisPitch(int distance, int maxAxis) {
            float closeness = 1.0f - Math.min(1.0f, distance / Math.max(1.0f, (float) maxAxis));
            return 180.0f + 1040.0f * closeness * closeness;
        }

        private static void playChord() {
            playTone(523.25f, 95, 0.42f);
            playTone(659.25f, 110, 0.42f);
            playTone(783.99f, 125, 0.44f);
            playTone(1046.5f, 170, 0.38f);
        }

        private static void playTone(float frequency, int durationMs, float volume) {
            int sampleCount = Math.max(1, SAMPLE_RATE * durationMs / 1000);
            short[] pcm = new short[sampleCount];
            double phaseStep = Math.PI * 2.0 * frequency / SAMPLE_RATE;
            int attackSamples = Math.max(1, SAMPLE_RATE * 5 / 1000);
            int releaseSamples = Math.max(1, SAMPLE_RATE * 8 / 1000);
            for (int i = 0; i < sampleCount; i++) {
                float envelope = 1.0f;
                if (i < attackSamples) {
                    envelope = i / (float) attackSamples;
                } else if (i > sampleCount - releaseSamples) {
                    envelope = Math.max(0.0f, (sampleCount - i) / (float) releaseSamples);
                }
                double sample = Math.sin(i * phaseStep) * volume * envelope;
                pcm[i] = (short) (sample * Short.MAX_VALUE);
            }

            AudioTrack track = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_GAME)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .setBufferSizeInBytes(pcm.length * 2)
                            .build();
                } else {
                    track = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            pcm.length * 2,
                            AudioTrack.MODE_STATIC);
                }
                track.write(pcm, 0, pcm.length);
                track.play();
                SystemClock.sleep(durationMs + 24L);
            } catch (RuntimeException ignored) {
                // Audio is a cue layer; gameplay should continue if the device rejects a buffer.
            } finally {
                if (track != null) {
                    track.release();
                }
            }
        }
    }
}
