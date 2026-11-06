package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Random;

/** Local jump simulation in density-independent world coordinates. */
public final class GhostDoodlePhysics {
    public static final float GAP = 132, JUMP = 620, GRAVITY = 1450, RADIUS = 12;
    public static final class Platform {
        public float x;
        public final float y, width, height, baseX, moveRange, moveSpeed, phase;
        Platform(float x, float y, float width, float height, float moveRange, float moveSpeed, float phase) {
            this.x = this.baseX = x; this.y = y; this.width = width; this.height = height;
            this.moveRange = moveRange; this.moveSpeed = moveSpeed; this.phase = phase;
        }
        public boolean isMoving() { return moveRange > 0; }
    }
    public final ArrayList<Platform> platforms = new ArrayList<>();
    private final Random random = new Random();
    public float width, height, x, y, velocity, camera;
    private float elapsed;
    public int reached;
    public boolean lost;
    public GhostDoodlePhysics(float width, float height) { this.width = width; this.height = height; restart(); }
    public void addPlatform() {
        int index = platforms.size();
        float size = Math.min(118, width * .38f);
        float center = width / 2;
        if (index > 0) {
            Platform previous = platforms.get(index - 1);
            float maxShift = Math.max(0, Math.min(width * .38f, 128));
            float shift = (random.nextFloat() * 2f - 1f) * maxShift;
            center = Math.max(size / 2 + 8, Math.min(width - size / 2 - 8, previous.baseX + previous.width / 2 + shift));
        }
        float x = Math.max(8, Math.min(width - size - 8, center - size / 2));
        boolean moving = index > 1 && random.nextFloat() < .28f;
        float range = moving ? Math.min(44, Math.max(12, (width - size) * .14f)) : 0;
        platforms.add(new Platform(x, 64 + index * GAP, size, 30, range,
                moving ? 0.55f + random.nextFloat() * .65f : 0, random.nextFloat() * (float) (Math.PI * 2)));
    }
    public void addMessagePlatform(boolean outgoing, float bubbleWidth, float bubbleHeight) {
        Platform previous = platforms.isEmpty() ? null : platforms.get(platforms.size() - 1);
        float top = previous == null ? bubbleHeight + 20 : previous.y + previous.height + GAP;
        float half = width / 2f;
        float minX = outgoing ? half : 8;
        float maxX = outgoing ? width - bubbleWidth - 8 : Math.min(half - bubbleWidth, width - bubbleWidth - 8);
        float alignedX = outgoing ? width - bubbleWidth - 12 : 12;
        float jitter = Math.min(42, Math.max(0, (maxX - minX) * .55f));
        float x = maxX < minX ? alignedX : Math.max(minX, Math.min(maxX, alignedX + (random.nextFloat() * 2 - 1) * jitter));
        float availableMovement = Math.min(x - minX, maxX - x);
        boolean moving = previous != null && platforms.size() > 2 && random.nextFloat() < .2f && availableMovement >= 8;
        float range = moving ? Math.min(24, availableMovement) : 0;
        platforms.add(new Platform(x, top, bubbleWidth, bubbleHeight, range,
                moving ? .45f + random.nextFloat() * .5f : 0, random.nextFloat() * (float) (Math.PI * 2)));
        if (platforms.size() == 1) restart();
    }
    private float jumpAt(int index) {
        float gap = index + 1 < platforms.size() ? platforms.get(index + 1).y - platforms.get(index).y : GAP;
        return Math.max(JUMP, (float) Math.sqrt(2 * GRAVITY * (gap + 64)));
    }
    public void restart() {
        x = platforms.isEmpty() ? width / 2 : platforms.get(0).x + platforms.get(0).width / 2;
        y = platforms.isEmpty() ? 64 : platforms.get(0).y;
        velocity = jumpAt(0); camera = Math.max(0, y - height * .58f); reached = 0; lost = false; elapsed = 0;
    }
    public void stepTilt(float seconds, float tilt) {
        float dt = Math.min(.033f, Math.max(0, seconds));
        float direction = Math.abs(tilt) < .04f ? 0 : Math.max(-1, Math.min(1, tilt));
        step(seconds, x + direction * 320 * dt);
    }
    public void step(float seconds, Float target) {
        if (lost || platforms.isEmpty()) return;
        float dt = Math.min(.033f, Math.max(0, seconds));
        elapsed += dt;
        for (Platform platform : platforms) {
            if (platform.isMoving()) {
                platform.x = Math.max(8, Math.min(width - platform.width - 8,
                        platform.baseX + (float) Math.sin(elapsed * platform.moveSpeed + platform.phase) * platform.moveRange));
            }
        }
        if (target != null) x += Math.max(-320 * dt, Math.min(320 * dt, target - x));
        x = Math.max(RADIUS, Math.min(width - RADIUS, x));
        float old = y;
        velocity -= GRAVITY * dt;
        y += velocity * dt;
        if (velocity < 0) {
            for (int index = platforms.size() - 1; index >= 0; index--) {
                Platform platform = platforms.get(index);
                if (old >= platform.y && y <= platform.y && x + RADIUS > platform.x && x - RADIUS < platform.x + platform.width) {
                    y = platform.y; velocity = jumpAt(index); reached = Math.max(reached, index); break;
                }
            }
        }
        camera = Math.max(camera, y - height * .58f);
        lost = y - camera < -40;
    }
}
