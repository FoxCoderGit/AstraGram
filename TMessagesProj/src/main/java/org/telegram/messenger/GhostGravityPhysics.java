package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Random;

/** Local oriented rectangles, SAT contacts and angular impulses. Coordinates are dp. */
public final class GhostGravityPhysics {
    public static final float FIXED_STEP = 1f / 240f;
    public final ArrayList<Body> bodies = new ArrayList<>();
    public final float width, height;
    private final Random random;
    private float accumulator, grabX, grabY, heldMotion;
    public Body held;
    public static final class Body {
        public float x, y, vx, vy, angle, omega;
        public final float width, height, mass, inverseMass, inverseInertia;
        public float u0, v0, u1 = 1, v1 = 1, life = -1;
        public boolean alive = true, fragment;
        public Object asset;
        float cos = 1, sin, radiusX, radiusY, priorAngle;
        Body(float x, float y, float width, float height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            mass = Math.max(.3f, width * height / 8000f); inverseMass = 1 / mass;
            inverseInertia = 12 / (mass * (width * width + height * height)); update();
        }
        void update() {
            cos = (float)Math.cos(angle); sin = (float)Math.sin(angle);
            radiusX = Math.abs(cos) * width / 2 + Math.abs(sin) * height / 2;
            radiusY = Math.abs(sin) * width / 2 + Math.abs(cos) * height / 2;
        }
    }
    public GhostGravityPhysics(float width, float height) { this(width, height, new Random()); }
    public GhostGravityPhysics(float width, float height, Random random) {
        this.width = Math.max(32, width); this.height = Math.max(32, height); this.random = random;
    }
    public Body add(float x, float y, float width, float height) {
        Body body = new Body(x, y, Math.max(4, width), Math.max(4, height)); bodies.add(body); return body;
    }
    public void step(float elapsed) {
        accumulator += Math.max(0, Math.min(.05f, elapsed));
        while (accumulator >= FIXED_STEP) { integrate(); accumulator -= FIXED_STEP; }
    }
    private void integrate() {
        heldMotion -= FIXED_STEP;
        for (Body body : bodies) {
            if (!body.alive) continue;
            if (body.fragment && (body.life -= FIXED_STEP) <= 0) { body.alive = false; continue; }
            body.priorAngle = body.angle;
            if (body == held) { if (heldMotion <= 0) body.vx = body.vy = 0; }
            else {
                body.vy += 1500 * FIXED_STEP;
                body.vx *= .9996f; body.omega *= .998f;
                body.x += body.vx * FIXED_STEP; body.y += body.vy * FIXED_STEP;
                body.angle += body.omega * FIXED_STEP;
            }
            body.update();
            if (body != held && body.y + body.radiusY < -160) body.alive = false;
        }
        for (int iteration = 0; iteration < 8; iteration++) {
            for (int i = 0; i < bodies.size(); i++) {
                Body a = bodies.get(i); if (!a.alive) continue;
                walls(a);
                for (int j = i + 1; j < bodies.size(); j++) {
                    Body b = bodies.get(j);
                    if (b.alive && Math.abs(a.x - b.x) < a.radiusX + b.radiusX && Math.abs(a.y - b.y) < a.radiusY + b.radiusY) collide(a, b);
                }
            }
        }
        for (Body body : bodies) { body.vx = limit(body.vx, 2400); body.vy = limit(body.vy, 2400); body.omega = limit(body.omega, 14); }
        bodies.removeIf(body -> !body.alive);
    }
    private float im(Body body) { return body == held ? 0 : body.inverseMass; }
    private float ii(Body body) { return body == held ? 0 : body.inverseInertia; }
    private static float projection(Body body, float nx, float ny) {
        return Math.abs(nx * body.cos + ny * body.sin) * body.width / 2
            + Math.abs(-nx * body.sin + ny * body.cos) * body.height / 2;
    }
    private void collide(Body a, Body b) {
        float dx = b.x - a.x, dy = b.y - a.y, overlap = Float.MAX_VALUE, nx = 0, ny = 0;
        for (int axis = 0; axis < 4; axis++) {
            Body body = axis < 2 ? a : b;
            float ax = (axis & 1) == 0 ? body.cos : -body.sin;
            float ay = (axis & 1) == 0 ? body.sin : body.cos;
            float distance = dx * ax + dy * ay;
            float penetration = projection(a, ax, ay) + projection(b, ax, ay) - Math.abs(distance);
            if (penetration <= 0) return;
            if (penetration < overlap) { overlap = penetration; nx = distance < 0 ? -ax : ax; ny = distance < 0 ? -ay : ay; }
        }
        float ma = im(a), mb = im(b), sum = ma + mb; if (sum == 0) return;
        float correction = Math.max(0, overlap - .25f) * .45f / sum;
        a.x -= nx * correction * ma; a.y -= ny * correction * ma;
        b.x += nx * correction * mb; b.y += ny * correction * mb;
        // Midpoint of the shared contact interval along the tangent.
        float tx = -ny, ty = nx, ta = a.x * tx + a.y * ty, tb = b.x * tx + b.y * ty;
        float tangent = (Math.max(ta - projection(a, tx, ty), tb - projection(b, tx, ty))
            + Math.min(ta + projection(a, tx, ty), tb + projection(b, tx, ty))) / 2;
        float normal = (a.x * nx + a.y * ny + projection(a, nx, ny)
            + b.x * nx + b.y * ny - projection(b, nx, ny)) / 2;
        float cx = nx * normal + tx * tangent, cy = ny * normal + ty * tangent;
        impulse(a, b, nx, ny, cx, cy);
    }
    private void impulse(Body a, Body b, float nx, float ny, float cx, float cy) {
        float ax = cx - a.x, ay = cy - a.y, bx = cx - b.x, by = cy - b.y;
        float dx = b.vx - b.omega * by - a.vx + a.omega * ay;
        float dy = b.vy + b.omega * bx - a.vy - a.omega * ax;
        float speed = dx * nx + dy * ny; if (speed >= 0) return;
        float ca = ax * ny - ay * nx, cb = bx * ny - by * nx;
        float amount = -(1 + (speed < -90 ? .18f : 0)) * speed / (im(a) + im(b) + ca * ca * ii(a) + cb * cb * ii(b));
        apply(a, -amount * nx, -amount * ny, ax, ay); apply(b, amount * nx, amount * ny, bx, by);
        float tx = -ny, ty = nx;
        dx = b.vx - b.omega * by - a.vx + a.omega * ay;
        dy = b.vy + b.omega * bx - a.vy - a.omega * ax;
        ca = ax * ty - ay * tx; cb = bx * ty - by * tx;
        float friction = limit(-(dx * tx + dy * ty) / (im(a) + im(b) + ca * ca * ii(a) + cb * cb * ii(b)), amount * .38f);
        apply(a, -friction * tx, -friction * ty, ax, ay); apply(b, friction * tx, friction * ty, bx, by);
    }
    private void apply(Body body, float x, float y, float rx, float ry) {
        body.vx += x * im(body); body.vy += y * im(body); body.omega += (rx * y - ry * x) * ii(body);
    }
    private void walls(Body body) {
        if (body == held) return;
        if (body.radiusX * 2 > width && body.width <= width) {
            body.angle = body.priorAngle; body.omega *= -.3f; body.update();
        }
        if (body.x - body.radiusX < 0) wall(body, 1, 0, body.radiusX - body.x);
        if (body.x + body.radiusX > width) wall(body, -1, 0, body.x + body.radiusX - width);
        if (body.y + body.radiusY > height) wall(body, 0, -1, body.y + body.radiusY - height);
    }
    private void wall(Body body, float nx, float ny, float penetration) {
        body.x += nx * penetration; body.y += ny * penetration;
        float localX = -nx * body.cos - ny * body.sin, localY = nx * body.sin - ny * body.cos;
        float px = Math.abs(localX) < .02f ? 0 : Math.signum(localX) * body.width / 2;
        float py = Math.abs(localY) < .02f ? 0 : Math.signum(localY) * body.height / 2;
        float rx = px * body.cos - py * body.sin, ry = px * body.sin + py * body.cos;
        float speed = (body.vx - body.omega * ry) * nx + (body.vy + body.omega * rx) * ny;
        if (speed >= 0) return;
        float cross = rx * ny - ry * nx;
        float amount = -(1 + (speed < -90 ? .2f : 0)) * speed / (body.inverseMass + cross * cross * body.inverseInertia);
        apply(body, amount * nx, amount * ny, rx, ry);
        float tx = -ny, ty = nx; cross = rx * ty - ry * tx;
        float friction = limit(-((body.vx - body.omega * ry) * tx + (body.vy + body.omega * rx) * ty)
            / (body.inverseMass + cross * cross * body.inverseInertia), amount * .5f);
        apply(body, friction * tx, friction * ty, rx, ry);
    }
    public Body pick(float x, float y) {
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Body body = bodies.get(i); float dx = x - body.x, dy = y - body.y;
            if (body.alive && Math.abs(dx * body.cos + dy * body.sin) <= body.width / 2
                    && Math.abs(-dx * body.sin + dy * body.cos) <= body.height / 2) return body;
        }
        return null;
    }
    public void grab(Body body, float x, float y) {
        held = body; grabX = x - body.x; grabY = y - body.y; body.vx = body.vy = body.omega = 0;
    }
    public void move(float x, float y, float elapsed) {
        if (held == null) return;
        float nextX = x - grabX, nextY = y - grabY;
        held.vx = limit((nextX - held.x) / Math.max(.008f, elapsed), 2400);
        held.vy = limit((nextY - held.y) / Math.max(.008f, elapsed), 2400);
        held.x = nextX; held.y = nextY; heldMotion = .04f;
    }
    public void release(float vx, float vy) {
        if (held == null) return;
        held.vx = limit(vx, 2400); held.vy = limit(vy, 2400);
        held.omega = limit((grabX * held.vy - grabY * held.vx) * 8 / (held.width * held.width + held.height * held.height), 12);
        held = null;
    }
    public ArrayList<Body> shatter(Body original) {
        ArrayList<Body> pieces = new ArrayList<>();
        if (!original.alive || original.fragment) return pieces;
        original.alive = false; if (held == original) held = null;
        int rows = 2 + random.nextInt(3);
        for (int row = 0; row < rows; row++) for (int column = 0; column < 2; column++) {
            float w = original.width / 2, h = original.height / rows;
            float dx = (column + .5f) * w - original.width / 2, dy = (row + .5f) * h - original.height / 2;
            Body piece = add(original.x + dx * original.cos - dy * original.sin, original.y + dx * original.sin + dy * original.cos, w, h);
            piece.angle = original.angle; piece.update(); piece.asset = original.asset; piece.fragment = true;
            piece.u0 = column / 2f; piece.u1 = (column + 1) / 2f; piece.v0 = (float)row / rows; piece.v1 = (float)(row + 1) / rows;
            piece.vx = original.vx + (column == 0 ? -1 : 1) * (180 + random.nextFloat() * 220);
            piece.vy = original.vy - 240 - random.nextFloat() * 260; piece.omega = (random.nextFloat() - .5f) * 14;
            piece.life = 1.4f + random.nextFloat() * .5f; pieces.add(piece);
        }
        return pieces;
    }
    private static float limit(float value, float max) { return Math.max(-max, Math.min(max, value)); }
}
