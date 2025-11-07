import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * SquareShooterExperimental - komplette Version mit:
 * - dynamischer Fenstergröße (Map passt sich an)
 * - Towers (kaufen mit Score, platzieren, automatisches Schießen)
 * - Shield-Saw (30s aktiv, 5min cooldown) blockt Bullets + leichtes Schaden an Gegnern
 * - Wandtypen: STOP, SLOW, THROUGH, DESTRUCTIBLE (mit HP)
 * - Friendly-Fire toggle (Taste F) -> Gegnerschüsse können Gegner verletzen (optional)
 * - Spawn-Limits (maxActiveEnemies) und maxTotalEnemies per Wave
 * - Wave-System: wenn Wave cleared -> Bonus + Press SPACE for next wave
 * - Besseres HUD, Title Screen, Game Over + Restart (SPACE)
 * - Gameplay: Platziermodus friert Spiel-Update (außer Rendering & Maus)
 *
 * Steuerung:
 * WASD - bewegen
 * Maus - zielen
 * Linke Maustaste - feuern / Turm platzieren wenn im Place-Modus
 * 1 - Tower kaufen (je 100 Score) - platziermodus; ESC cancel + refund remainder
 * 2 - Shield-Saw toggle (aktiviert, 30s, 300s cooldown)
 * F - Friendly fire toggle (Gegnerkugeln können Gegner verletzen)
 * SPACE - Start / Restart / Next Wave
 * ESC - cancel placing
 */
public class SquareShooterUpgraded extends JPanel implements KeyListener, MouseMotionListener, MouseListener {
    // --- initial preferred size (window is resizable) ---
    private final int prefWidth = 1000, prefHeight = 800;

    // --- runtime window size (updated each loop) ---
    private int screenW = prefWidth, screenH = prefHeight;

    // --- Spieler ---
    private int playerX = prefWidth / 2, playerY = prefHeight / 2;
    private int playerSize = 30;
    private boolean up, down, left, right;
    private int mouseX = prefWidth / 2, mouseY = prefHeight / 2;
    private int playerHP = 100;
    private final int playerHPMax = 100;
    private int totalDamageTaken = 0;
    private int lastDamage = 0;
    private long lastDamageTime = 0;

    private int ammo = 35;
    private boolean reloading = false;
    private long reloadStart = 0;
    private boolean mouseDown = false;

    // --- Shooting / Fire rate ---
    private long lastShot = 0;
    private int fireDelay = 500;

    // --- Collections ---
    private final List<Bullet> bullets = new ArrayList<>();
    private final List<Enemy> enemies = new ArrayList<>();
    private final List<EnemyBullet> enemyBullets = new ArrayList<>();
    private final List<Wall> walls = new ArrayList<>();
    private final List<HealthPack> healthPacks = new ArrayList<>();

    private int totalHealthPacks = 0;

    // --- Upgrades & Specials ---
    private int swordCount = 0;
    private double swordAngle = 0;
    private long lastUpgradeScore = 0;
    private int reloadTime = 5000;
    private int bulletSize = 6;

    private final List<Tower> towers = new ArrayList<>();
    private boolean placingTower = false;
    private int towersToPlace = 0; // number of towers bought, to place sequentially

    // Shield saw
    private boolean shieldActive = false;
    private Shield shield;
    private long lastShieldUsed = -999999999L;
    private final long shieldDuration = 30_000L; // 30s
    private final long shieldCooldown = 300_000L; // 5min
    private long shieldActivatedAt = 0L;

    // Friendly fire toggle (default OFF)
    private boolean friendlyFire = false;

    // --- Game state ---
    private int totalSpawned = 0;
    private int score = 0;
    private boolean gameOver = false;
    private boolean titleScreen = true;
    private final int maxActiveEnemies = 30; // active concurrent enemies
    private int maxTotalEnemies = 50; // per wave
    private boolean waveCleared = false;
    private String notifyMessage = "";

    private final Random rnd = new Random();

    public SquareShooterUpgraded() {
        setPreferredSize(new Dimension(prefWidth, prefHeight));
        setBackground(Color.BLACK);

        JFrame frame = new JFrame("Square Shooter — Experimental");
        frame.add(this);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.addKeyListener(this);
        frame.addMouseMotionListener(this);
        frame.addMouseListener(this);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // initial walls (example layout)
        walls.add(new Wall(300, 200, 60, 200, Wall.Type.STOP));
        walls.add(new Wall(600, 400, 120, 40, Wall.Type.SLOW));
        walls.add(new Wall(450, 650, 100, 40, Wall.Type.DESTRUCTIBLE, 35));
        walls.add(new Wall(150, 150, 40, 300, Wall.Type.THROUGH));

        // Game loops (timers)
        new javax.swing.Timer(16, e -> gameLoop()).start();                 // main loop ~60fps
        new javax.swing.Timer(1800, e -> spawnEnemy()).start();            // spawn attempt
        new javax.swing.Timer(120, e -> { if (!gameOver && mouseDown && !placingTower) fireBullet(); }).start();
        new javax.swing.Timer(30000, e -> spawnHealthPacks()).start();     // healthpack spawner
    }

    // --- spawn enemy with screen size awareness ---
    private void spawnEnemy() {
        if (gameOver || titleScreen) return;
        if (totalSpawned >= maxTotalEnemies) return;
        if (enemies.size() >= maxActiveEnemies) return;

        int side = rnd.nextInt(4);
        int x = (side == 0) ? 0 : (side == 1) ? screenW : rnd.nextInt(Math.max(1, screenW));
        int y = (side == 2) ? 0 : (side == 3) ? screenH : rnd.nextInt(Math.max(1, screenH));
        int type = rnd.nextInt(3);
        enemies.add(new Enemy(x, y, type));
        totalSpawned++;
    }

    private void spawnHealthPacks() {
        if (gameOver || titleScreen) return;
        int count = 1 + rnd.nextInt(3);
        for (int i = 0; i < count; i++) {
            int x = rnd.nextInt(Math.max(1, screenW - 100)) + 50;
            int y = rnd.nextInt(Math.max(1, screenH - 100)) + 50;
            healthPacks.add(new HealthPack(x, y));
            totalHealthPacks++;
        }
    }

    // --- main game loop ---
    private void gameLoop() {
        // keep screen size in sync with window (map adapts)
        screenW = Math.max(200, getWidth());
        screenH = Math.max(200, getHeight());

        if (titleScreen) { repaint(); return; }
        if (gameOver) { repaint(); return; }

        // Freeze gameplay updates while placing tower (renders still run)
        if (placingTower) { repaint(); return; }

        // --- player movement ---
        double vx = 0, vy = 0;
        if (up) vy -= 1;
        if (down) vy += 1;
        if (left) vx -= 1;
        if (right) vx += 1;
        double len = Math.sqrt(vx * vx + vy * vy);
        double speed = 5;
        if (len > 0) {
            vx = vx / len * speed;
            vy = vy / len * speed;
            int nextX = playerX + (int) vx;
            int nextY = playerY + (int) vy;
            Rectangle nextPos = new Rectangle(nextX - playerSize / 2, nextY - playerSize / 2, playerSize, playerSize);
            boolean collides = false;
            for (Wall w : walls) if (w.blocksPlayer() && nextPos.intersects(w.getBounds())) { collides = true; break; }
            if (!collides) { playerX = nextX; playerY = nextY; }
        }

        // keep player inside window
        playerX = Math.max(playerSize / 2, Math.min(screenW - playerSize / 2, playerX));
        playerY = Math.max(playerSize / 2, Math.min(screenH - playerSize / 2, playerY));

        // reload
        if (reloading && System.currentTimeMillis() - reloadStart >= reloadTime) {
            ammo = 35;
            reloading = false;
        }

        // --- bullets (player) update + wall interactions ---
        Iterator<Bullet> bit = bullets.iterator();
        while (bit.hasNext()) {
            Bullet b = bit.next();
            b.update();
            boolean removed = false;

            for (Wall w : new ArrayList<>(walls)) {
                if (w.getBounds().contains((int) b.x, (int) b.y)) {
                    switch (w.type) {
                        case STOP:
                            removed = true; // bullet stops
                            break;
                        case SLOW:
                            b.vx *= 0.5; b.vy *= 0.5; // slow down
                            break;
                        case DESTRUCTIBLE:
                            w.hp--;
                            if (w.hp <= 0) walls.remove(w);
                            // bullet passes through
                            break;
                        case THROUGH:
                            // nothing
                            break;
                    }
                    if (removed) break;
                }
            }
            if (removed) { bit.remove(); continue; }

            if (b.x < -50 || b.x > screenW + 50 || b.y < -50 || b.y > screenH + 50) { bit.remove(); continue; }
        }

        // --- enemy bullets update + wall interactions + collisions ---
        Iterator<EnemyBullet> ebit = enemyBullets.iterator();
        while (ebit.hasNext()) {
            EnemyBullet eb = ebit.next();
            eb.update();

            boolean collided = false;
            for (Wall w : new ArrayList<>(walls)) {
                if (w.getBounds().contains((int) eb.x, (int) eb.y)) {
                    switch (w.type) {
                        case STOP:
                            collided = true;
                            break;
                        case SLOW:
                            eb.vx *= 0.5; eb.vy *= 0.5;
                            break;
                        case DESTRUCTIBLE:
                            w.hp--;
                            if (w.hp <= 0) walls.remove(w);
                            break;
                        case THROUGH:
                            break;
                    }
                    if (collided) break;
                }
            }
            if (collided || eb.x < -80 || eb.x > screenW + 80 || eb.y < -80 || eb.y > screenH + 80) { ebit.remove(); continue; }

            // player hit (shield blocks)
            if (!shieldActive && rectContainsPoint(playerX - playerSize / 2, playerY - playerSize / 2, playerSize, playerSize, eb.x, eb.y)) {
                takeDamage(eb.damage);
                ebit.remove();
                continue;
            } else if (shieldActive && shield != null && shield.contains(eb.x, eb.y)) {
                // shield blocks bullet
                ebit.remove();
                continue;
            }

            // friendly-fire: enemy bullet can hit other enemies if enabled
            if (friendlyFire) {
                for (Enemy en : new ArrayList<>(enemies)) {
                    if (en == eb.owner) continue; // don't hit owner immediately
                    if (en.getBounds().contains((int) eb.x, (int) eb.y)) {
                        en.hp -= eb.damage;
                        ebit.remove();
                        break;
                    }
                }
            }
        }

        // --- enemies update ---
        for (Enemy en : new ArrayList<>(enemies)) {
            en.update(playerX, playerY, enemyBullets, walls);
            Rectangle playerRect = new Rectangle(playerX - playerSize / 2, playerY - playerSize / 2, playerSize, playerSize);
            if (playerRect.intersects(en.getBounds()) && !shieldActive) {
                takeDamage(en.contactDamage());
            }
        }

        // --- bullets vs enemies ---
        for (Bullet b : new ArrayList<>(bullets)) {
            for (Enemy en : new ArrayList<>(enemies)) {
                if (en.getBounds().contains((int) b.x, (int) b.y)) {
                    en.hp -= b.damage;
                    b.dead = true;
                }
            }
        }
        bullets.removeIf(b -> b.dead);

        // --- remove dead enemies + score ---
        enemies.removeIf(en -> {
            if (en.hp <= 0) {
                score += en.type == 2 ? 5 : (en.type == 1 ? 3 : 2);
                return true;
            }
            return false;
        });

        // --- health packs pickup ---
        Iterator<HealthPack> it = healthPacks.iterator();
        Rectangle playerRect = new Rectangle(playerX - playerSize / 2, playerY - playerSize / 2, playerSize, playerSize);
        while (it.hasNext()) {
            HealthPack hp = it.next();
            if (playerRect.intersects(hp.getBounds())) {
                playerHP = Math.min(playerHPMax, playerHP + 25);
                it.remove();
            } else if (System.currentTimeMillis() - hp.spawnTime > 15000) it.remove();
        }

        // --- upgrades progression ---
        checkUpgrades();
        swordAngle += 0.05;

        // --- towers ---
        for (Tower t : towers) t.update(enemies, bullets);

        // --- shield ---
        if (shieldActive && shield != null) {
            shield.update(enemies);
            if (System.currentTimeMillis() - shieldActivatedAt > shieldDuration) {
                shieldActive = false;
                lastShieldUsed = System.currentTimeMillis();
                shield = null;
            }
        }

        // --- wave cleared handling ---
        if (totalSpawned >= maxTotalEnemies && enemies.isEmpty() && !waveCleared) {
            waveCleared = true;
            notifyMessage = "WAVE CLEARED! Bonus: +20 score, Press SPACE to continue";
            score += 20;
            // small heal bonus
            playerHP = Math.min(playerHPMax, playerHP + 20);
        }

        repaint();
    }

    private void takeDamage(int dmg) {
        playerHP -= dmg;
        totalDamageTaken += dmg;
        lastDamage = dmg;
        lastDamageTime = System.currentTimeMillis();
        if (playerHP <= 0) { playerHP = 0; gameOver = true; }
    }

    private void checkUpgrades() {
        if (score - lastUpgradeScore < 10) return;
        if (score >= 10 && swordCount == 0) swordCount = 1;
        if (score >= 20 && swordCount == 1) swordCount = 2;
        if (score >= 30 && swordCount == 2) swordCount = 3;
        if (score >= 40 && swordCount == 3) swordCount = 4;
        if (score >= 40) reloadTime = 3000;
        if (score >= 50 && swordCount == 4) swordCount = 5;
        if (score >= 60 && swordCount == 5) swordCount = 6;
        if (score >= 60) bulletSize = 9;
        if (score >= 80 && playerHP < playerHPMax) playerHP += 25;
        if (score >= 100) reloadTime = 1500;
        if (score >= 120 && playerHP < playerHPMax) playerHP += 45;
        if (score >= 140) reloadTime = 500;
        if (score >= 160) reloadTime = 150;
        if (score >= 200) bulletSize = 20;

        // Fire rate upgrades
        if (score >= 20 && fireDelay > 400) fireDelay = 400;
        if (score >= 60 && fireDelay > 300) fireDelay = 300;
        if (score >= 120 && fireDelay > 200) fireDelay = 200;
        if (score >= 180 && fireDelay > 120) fireDelay = 120;
        if (score >= 250 && fireDelay > 80) fireDelay = 80;
        if (score >= 500 && fireDelay > 1) fireDelay = 1;
        lastUpgradeScore = score;
    }

    private void fireBullet() {
        if (gameOver || reloading || placingTower) return;
        long now = System.currentTimeMillis();
        if (now - lastShot < fireDelay) return;

        if (ammo <= 0) { reloading = true; reloadStart = System.currentTimeMillis(); return; }

        double dx = mouseX - playerX, dy = mouseY - playerY;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return;
        double speed = 10;
        bullets.add(new Bullet(playerX, playerY, dx / len * speed, dy / len * speed, 1));
        ammo--;
        lastShot = now;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (titleScreen) {
            g2.setColor(Color.WHITE);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 48f));
            String txt = "SQUARE SHOOTER EXPERIMENTAL";
            int tw = g2.getFontMetrics().stringWidth(txt);
            g2.drawString(txt, screenW / 2 - tw / 2, screenH / 2 - 50);
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 20f));
            String start = "Press SPACE to Start";
            g2.drawString(start, screenW / 2 - g2.getFontMetrics().stringWidth(start) / 2, screenH / 2 + 10);

            String help = "WASD Move • Mouse Aim • LMB Fire • 1 Buy Tower • 2 Shield • F Friendly-Fire Toggle";
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14f));
            g2.drawString(help, screenW / 2 - g2.getFontMetrics().stringWidth(help) / 2, screenH / 2 + 40);
            return;
        }

        // --- Walls ---
        for (Wall w : walls) w.draw(g2);

        // --- player ---
        drawNeonRect(g2, playerX - playerSize / 2, playerY - playerSize / 2, playerSize, playerSize, new Color(0, 255, 255), 3);
        if (System.currentTimeMillis() - lastDamageTime < 600) {
            g2.setColor(Color.RED);
            g2.drawString("-" + lastDamage, playerX - 10, playerY - 40);
        }

        // --- swords
        if (swordCount > 0) drawSwords(g2);

        // --- bullets ---
        g2.setColor(Color.WHITE);
        for (Bullet b : bullets) g2.fillOval((int) b.x - bulletSize / 2, (int) b.y - bulletSize / 2, bulletSize, bulletSize);

        // --- enemy bullets ---
        g2.setColor(new Color(255, 0, 255));
        for (EnemyBullet eb : enemyBullets) g2.fillOval((int) eb.x - 4, (int) eb.y - 4, 8, 8);

        // --- enemies ---
        for (Enemy en : enemies) en.draw(g2);

        // --- health packs ---
        for (HealthPack hp : healthPacks) hp.draw(g2);

        // --- towers ---
        for (Tower t : towers) t.draw(g2);

        // --- shield ---
        if (shieldActive && shield != null) shield.draw(g2);

        // --- placing preview ---
        if (placingTower) {
            g2.setColor(new Color(255, 255, 0, 120));
            g2.fillRect(mouseX - 15, mouseY - 15, 30, 30);
            g2.setColor(Color.WHITE);
            g2.drawString("Click to place tower (" + towersToPlace + " left). Press ESC to cancel.", 10, screenH - 20);
        }

        drawHUD(g2);

        // Wave cleared text
        if (waveCleared) {
            g2.setColor(new Color(255, 255, 255, 220));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 28f));
            int tw = g2.getFontMetrics().stringWidth(notifyMessage);
            g2.drawString(notifyMessage, screenW / 2 - tw / 2, screenH / 2);
        }

        if (gameOver) {
            g2.setColor(new Color(255, 255, 255, 200));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 36f));
            String txt = "GAME OVER";
            int tw = g2.getFontMetrics().stringWidth(txt);
            g2.drawString(txt, screenW / 2 - tw / 2, screenH / 2);

            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 24f));
            String restart = "Press SPACE to Restart";
            g2.drawString(restart, screenW / 2 - g2.getFontMetrics().stringWidth(restart) / 2, screenH / 2 + 40);
        }
    }

    private void drawSwords(Graphics2D g2) {
        for (int i = 0; i < swordCount; i++) {
            double angle = swordAngle * (i % 2 == 0 ? 1 : -1) + (i * Math.PI / swordCount);
            int radius = 50;
            int sx = playerX + (int) (Math.cos(angle) * radius);
            int sy = playerY + (int) (Math.sin(angle) * radius);
            g2.setColor(new Color(0, 255, 255, 150));
            g2.fillRect(sx - 5, sy - 15, 10, 30);
            Polygon tri = new Polygon();
            tri.addPoint(sx, sy - 20);
            tri.addPoint(sx - 7, sy - 10);
            tri.addPoint(sx + 7, sy - 10);
            g2.fillPolygon(tri);
            for (Enemy en : enemies) if (en.getBounds().contains(sx, sy)) en.hp -= 1;
        }
    }

    private void drawHUD(Graphics2D g2) {
        // panel background
        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRoundRect(6, 6, 260, 260, 10, 10);

        // HP Bar
        g2.setColor(Color.DARK_GRAY);
        g2.fillRect(10, 10, 204, 18);
        g2.setColor(Color.RED);
        g2.drawRect(10, 10, 204, 18);
        int hpw = (int) (200.0 * playerHP / playerHPMax);
        g2.setColor(Color.GREEN);
        g2.fillRect(12, 12, hpw, 14);

        g2.setColor(Color.WHITE);
        g2.drawString(reloading ? "Reloading..." : "Ammo: " + ammo + "/35", 10, 40);
        g2.drawString("Score: " + score, 10, 60);
        g2.drawString("Spawned: " + totalSpawned + "/" + maxTotalEnemies, 10, 80);
        g2.drawString("Damage Taken: " + totalDamageTaken, 10, 100);
        g2.drawString("Upgrades: " + swordCount + " swords", 10, 120);
        g2.drawString("Health Packs: " + totalHealthPacks, 10, 140);

        double shotsPerSec = fireDelay > 0 ? 1000.0 / fireDelay : 0;
        g2.drawString(String.format("Fire Rate: %.2f shots/s", shotsPerSec), 10, 160);

        // Shield status
        String shieldStatus;
        if (shieldActive) {
            long left = Math.max(0, shieldDuration - (System.currentTimeMillis() - shieldActivatedAt));
            shieldStatus = "Shield: ACTIVE (" + (left / 1000) + "s)";
        } else {
            long untilReady = Math.max(0, (lastShieldUsed + shieldCooldown) - System.currentTimeMillis());
            shieldStatus = untilReady <= 0 ? "Shield: READY (Press 2)" : "Shield CD: " + (untilReady / 1000) + "s";
        }
        g2.drawString(shieldStatus, 10, 180);

        // Tower info
        g2.drawString("Towers: " + towers.size(), 10, 200);
        g2.drawString("Buy Tower: 100 Score -> press 1", 10, 220);
        g2.drawString("Shield Saw: press 2 (30s) cd 5min", 10, 240);
        g2.drawString("Friendly Fire (F): " + (friendlyFire ? "ON" : "OFF"), 10, 260);
    }

    private void drawNeonRect(Graphics2D g2, int x, int y, int w, int h, Color base, int stroke) {
        g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 120));
        g2.fillRect(x, y, w, h);
        g2.setStroke(new BasicStroke(stroke));
        g2.setColor(base);
        g2.drawRect(x, y, w, h);
        g2.setStroke(new BasicStroke(1));
    }

    private boolean rectContainsPoint(int rx, int ry, int rw, int rh, double px, double py) {
        return px >= rx && px <= rx + rw && py >= ry && py <= ry + rh;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_W: up = true; break;
            case KeyEvent.VK_S: down = true; break;
            case KeyEvent.VK_A: left = true; break;
            case KeyEvent.VK_D: right = true; break;

            case KeyEvent.VK_1:
                if (!placingTower && score >= 100) {
                    int purch = score / 100; // number of towers the player can afford
                    towersToPlace = Math.max(1, purch);
                    score -= towersToPlace * 100;
                    placingTower = true;
                    notifyMessage = "";
                } else {
                    if (score < 100) notifyMessage = "Not enough score for tower!";
                }
                break;

            case KeyEvent.VK_2:
                long now = System.currentTimeMillis();
                if (!shieldActive && now - lastShieldUsed >= shieldCooldown) {
                    shieldActive = true;
                    shield = new Shield(playerX, playerY, 100);
                    shieldActivatedAt = now;
                } else if (shieldActive) {
                    // manual deactivate
                    shieldActive = false;
                    shield = null;
                    lastShieldUsed = now;
                } else {
                    notifyMessage = "Shield on cooldown";
                }
                break;

            case KeyEvent.VK_F:
                friendlyFire = !friendlyFire;
                notifyMessage = "Friendly Fire " + (friendlyFire ? "ON" : "OFF");
                break;

            case KeyEvent.VK_SPACE:
                if (titleScreen) {
                    titleScreen = false;
                    gameOver = false;
                    resetForNewGame();
                    break;
                }
                if (gameOver) {
                    restartGame();
                    break;
                }
                if (waveCleared) {
                    startNextWave();
                    break;
                }
                break;

            case KeyEvent.VK_ESCAPE:
                if (placingTower) {
                    placingTower = false;
                    score += towersToPlace * 100; // refund
                    towersToPlace = 0;
                }
                break;
        }
    }

    @Override public void keyReleased(KeyEvent e) { switch (e.getKeyCode()) { case KeyEvent.VK_W: up = false; break; case KeyEvent.VK_S: down = false; break; case KeyEvent.VK_A: left = false; break; case KeyEvent.VK_D: right = false; break; } }
    @Override public void keyTyped(KeyEvent e) {}
    @Override public void mouseMoved(MouseEvent e) { mouseX = e.getX(); mouseY = e.getY(); }
    @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }
    @Override
    public void mousePressed(MouseEvent e) {
        mouseDown = true;
        if (placingTower && towersToPlace > 0) {
            // clamp placement inside screen
            int px = Math.max(20, Math.min(screenW - 20, mouseX));
            int py = Math.max(20, Math.min(screenH - 20, mouseY));
            towers.add(new Tower(px, py));
            towersToPlace--;
            if (towersToPlace <= 0) placingTower = false;
        } else {
            fireBullet();
        }
    }
    @Override public void mouseReleased(MouseEvent e) { mouseDown = false; }
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    private void restartGame() {
        playerHP = playerHPMax; totalDamageTaken = 0; ammo = 35; score = 0; totalSpawned = 0;
        bullets.clear(); enemies.clear(); enemyBullets.clear(); towers.clear(); healthPacks.clear();
        swordCount = 0; swordAngle = 0; reloadTime = 500; bulletSize = 6;
        gameOver = false; titleScreen = false;
        waveCleared = false; notifyMessage = "";
        maxTotalEnemies = 50;
        lastShieldUsed = -999999999L;
    }

    private void resetForNewGame() {
        restartGame();
    }

    private void startNextWave() {
        waveCleared = false;
        totalSpawned = 0;
        enemies.clear();
        maxTotalEnemies += 10; // ramp up difficulty per wave
        notifyMessage = "";
    }

    // --- inner classes ---

    static class Bullet {
        double x, y, vx, vy;
        int damage;
        boolean dead = false;
        Bullet(double x, double y, double vx, double vy, int damage) { this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.damage = damage; }
        void update() { x += vx; y += vy; }
    }

    class EnemyBullet {
        double x, y, vx, vy;
        int damage = 5;
        final Enemy owner; // to avoid hitting origin immediately
        EnemyBullet(double x, double y, double vx, double vy, Enemy owner) { this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.owner = owner; }
        void update() { x += vx; y += vy; }
    }

    class Enemy {
        int x, y, size, hp, maxHp, type;
        long lastAbility = 0L;
        Enemy(int x, int y, int type) {
            this.x = x; this.y = y; this.type = type;
            if (type == 0) { size = 18; hp = 4; }
            else if (type == 1) { size = 20; hp = 6; }
            else { size = 24; hp = 10; }
            maxHp = hp;
        }
        void update(int px, int py, List<EnemyBullet> ebList, List<Wall> walls) {
            double dx = px - x, dy = py - y;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len > 0) {
                double baseSpeed = (type == 0 ? 3.0 : (type == 1 ? 2.0 : 1.2));
                int nextX = x + (int) (dx / len * baseSpeed), nextY = y + (int) (dy / len * baseSpeed);
                Rectangle next = new Rectangle(nextX - size, nextY - size, size * 2, size * 2);
                boolean collide = false;
                for (Wall w : walls) if (w.blocksPlayer() && next.intersects(w.getBounds())) { collide = true; break; }
                if (!collide) { x = nextX; y = nextY; }
            }
            long now = System.currentTimeMillis();
            if (type == 1 && now - lastAbility > 900 && len > 0) {
                ebList.add(new EnemyBullet(x, y, dx / len * 4, dy / len * 4, this));
                lastAbility = now;
            } else if (type == 2 && now - lastAbility > 2000 && hp < maxHp) {
                hp = Math.min(maxHp, hp + 2);
                lastAbility = now;
            }
        }
        int contactDamage() { return type == 2 ? 3 : (type == 0 ? 2 : 1); }
        Rectangle getBounds() { return new Rectangle(x - size, y - size, size * 2, size * 2); }
        void draw(Graphics2D g2) {
            int barW = size * 2, hpw = (int) (barW * (hp / (double) maxHp));
            g2.setColor(Color.DARK_GRAY); g2.fillRect(x - size, y - size - 10, barW, 6);
            g2.setColor(Color.RED); g2.drawRect(x - size, y - size - 10, barW, 6);
            g2.setColor(Color.GREEN); g2.fillRect(x - size, y - size - 10, hpw, 6);
            if (type == 0) {
                Polygon tri = new Polygon();
                tri.addPoint(x, y - size);
                tri.addPoint(x - size, y + size);
                tri.addPoint(x + size, y + size);
                g2.setColor(new Color(255, 60, 60, 140));
                g2.fillPolygon(tri);
                g2.setColor(Color.RED);
                g2.drawPolygon(tri);
            } else if (type == 1) {
                g2.setColor(new Color(255, 0, 255, 140));
                g2.fillOval(x - size, y - size, size * 2, size * 2);
                g2.setColor(new Color(255, 0, 255));
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawOval(x - size, y - size, size * 2, size * 2);
                g2.setStroke(new BasicStroke(1f));
            } else {
                g2.setColor(new Color(255, 150, 0, 140));
                g2.fillRect(x - size, y - size, size * 2, size * 2);
                g2.setColor(new Color(255, 180, 0));
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawRect(x - size, y - size, size * 2, size * 2);
                g2.setStroke(new BasicStroke(1f));
            }
        }
    }

    class Wall {
        int x, y, w, h, hp = 0;
        Type type;
        boolean destructible = false;
        enum Type { STOP, SLOW, THROUGH, DESTRUCTIBLE }
        Wall(int x, int y, int w, int h, Type type) { this.x = x; this.y = y; this.w = w; this.h = h; this.type = type; destructible = type == Type.DESTRUCTIBLE; if (destructible) hp = 3; }
        Wall(int x, int y, int w, int h, Type type, int hp) { this(x, y, w, h, type); this.hp = hp; }
        Rectangle getBounds() { return new Rectangle(x, y, w, h); }
        boolean blocksPlayer() { return type == Type.STOP || type == Type.DESTRUCTIBLE; }
        boolean blocksBullets() { return type == Type.STOP || type == Type.SLOW || type == Type.DESTRUCTIBLE; }
        void draw(Graphics2D g2) {
            switch (type) {
                case STOP: g2.setColor(Color.GRAY); g2.fillRect(x, y, w, h); break;
                case SLOW: g2.setColor(Color.BLUE); g2.fillRect(x, y, w, h); break;
                case THROUGH: g2.setColor(Color.DARK_GRAY); g2.fillRect(x, y, w, h); break;
                case DESTRUCTIBLE: g2.setColor(new Color(200, 100, 0)); g2.fillRect(x, y, w, h); break;
            }
            if (destructible) {
                g2.setColor(Color.BLACK);
                g2.drawString("HP:" + hp, x + 2, y + 12);
            }
        }
    }

    class HealthPack {
        int x, y, size = 20;
        long spawnTime;
        HealthPack(int x, int y) { this.x = x; this.y = y; spawnTime = System.currentTimeMillis(); }
        void update() {}
        void draw(Graphics2D g2) {
            Polygon hex = new Polygon();
            for (int i = 0; i < 6; i++) {
                double angle = Math.toRadians(60 * i);
                int hx = x + (int) (Math.cos(angle) * size);
                int hy = y + (int) (Math.sin(angle) * size);
                hex.addPoint(hx, hy);
            }
            g2.setColor(new Color(0, 255, 100, 150));
            g2.fillPolygon(hex);
            g2.setColor(new Color(255, 50, 50));
            g2.setStroke(new BasicStroke(2.5f));
            g2.drawPolygon(hex);
            g2.setStroke(new BasicStroke(1f));
        }
        Rectangle getBounds() { return new Rectangle(x - size, y - size, size * 2, size * 2); }
    }

    class Tower {
        int x, y;
        double lastShotTime = 0;
        int range = 220;
        int level = 1;
        Tower(int x, int y) { this.x = x; this.y = y; }
        void update(List<Enemy> enemies, List<Bullet> bullets) {
            long now = System.currentTimeMillis();
            int cooldown = Math.max(100, 500 - (level - 1) * 100);
            if (now - lastShotTime < cooldown) return;
            Enemy target = null;
            double minDist = Double.MAX_VALUE;
            for (Enemy en : enemies) {
                double dx = en.x - x, dy = en.y - y, dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < minDist && dist <= range) { minDist = dist; target = en; }
            }
            if (target != null) {
                double dx = target.x - x, dy = target.y - y, len = Math.sqrt(dx * dx + dy * dy);
                bullets.add(new Bullet(x, y, dx / len * 10, dy / len * 10, 1 + (level - 1)));
                lastShotTime = now;
            }
        }
        void draw(Graphics2D g2) {
            g2.setColor(new Color(255, 255, 0, 190));
            g2.fillRect(x - 15, y - 15, 30, 30);
            g2.setColor(Color.BLACK);
            g2.drawRect(x - 15, y - 15, 30, 30);
            g2.setColor(Color.BLACK);
            g2.drawString("L" + level, x - 12, y + 4);
        }
        void upgrade() { level++; range += 30; }
    }

    class Shield {
        int x, y, radius;
        double angle = 0;
        Shield(int x, int y, int radius) { this.x = x; this.y = y; this.radius = radius; }
        void update(List<Enemy> enemies) {
            x = playerX; y = playerY;
            angle += 0.12;
            int sx = (int) (x + Math.cos(angle) * radius);
            int sy = (int) (y + Math.sin(angle) * radius);
            // saw effect: little damage on contact
            for (Enemy en : enemies) if (en.getBounds().contains(sx, sy)) en.hp -= 1;
        }
        boolean contains(double px, double py) {
            double dx = px - x, dy = py - y;
            return dx * dx + dy * dy <= radius * radius;
        }
        void draw(Graphics2D g2) {
            g2.setColor(new Color(0, 255, 255, 60));
            g2.fillOval(x - radius, y - radius, radius * 2, radius * 2);
            int sx = (int) (x + Math.cos(angle) * radius);
            int sy = (int) (y + Math.sin(angle) * radius);
            g2.setColor(new Color(0, 255, 255, 220));
            g2.fillOval(sx - 10, sy - 10, 20, 20);
        }
    }

    public static void main(String[] args) { SwingUtilities.invokeLater(SquareShooterUpgraded::new); }
}
