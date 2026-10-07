package com.example.cyberrunner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SD kártya / Külső tárhely engedélyek ellenőrzése
        checkPermissions()

        // Egyetlen egybefüggő GameView példányosítása és beállítása
        gameView = GameView(this)
        setContentView(gameView)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val writePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (writePermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                    101
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
    }

    override fun onResume() {
        super.onResume()
        gameView.resume()
    }

    // =========================================================================
    // EGYBEFÜGGŐ NATIVE JÁTÉKMOTOR (SurfaceView + Canvas 2D)
    // =========================================================================
    class GameView(context: Context) : SurfaceView(context), Runnable {

        private var thread: Thread? = null
        @Volatile
        private var isPlaying = false
        private val holder: SurfaceHolder = getHolder()

        // Képernyőméretek
        private var screenWidth = 0
        private var screenHeight = 0

        // Játékos pozíció és tulajdonságok
        private var playerX = 0f
        private var playerY = 0f
        private val playerRadius = 45f
        private var playerSpeedX = 0f

        // Játékelemek
        private val obstacles = mutableListOf<Obstacle>()
        private val powerUps = mutableListOf<PowerUp>()
        private val particles = mutableListOf<Particle>()

        // Állapotok és Pontszámok
        private var score = 0
        private var highScore = 0
        private var isGameOver = false
        private var activeShield = false
        private var slowMoTimer = 0
        private var doubleScoreTimer = 0

        // Grafikai rajzoló elemek (Paints)
        private val playerPaint = Paint().apply { color = Color.CYAN; isAntiAlias = true }
        private val obstaclePaint = Paint().apply { color = Color.MAGENTA; isAntiAlias = true }
        private val shieldPaint = Paint().apply {
            color = Color.GREEN
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 55f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

        init {
            loadHighScoreFromSD()
        }

        override fun run() {
            while (isPlaying) {
                update()
                draw()
                sleep()
            }
        }

        private fun update() {
            if (isGameOver) return

            // Játékos mozgatása
            playerX += playerSpeedX
            if (playerX - playerRadius < 0) playerX = playerRadius
            if (playerX + playerRadius > screenWidth) playerX = screenWidth - playerRadius

            val speedMultiplier = if (slowMoTimer > 0) 0.5f else 1.0f
            if (slowMoTimer > 0) slowMoTimer--
            if (doubleScoreTimer > 0) doubleScoreTimer--

            // Pontszám növelése
            score += if (doubleScoreTimer > 0) 2 else 1

            // Akadályok generálása és mozgása
            if (Math.random() < 0.04) {
                val obsX = (Math.random() * (screenWidth - 100) + 50).toFloat()
                val obsSpeed = (12..22).random().toFloat()
                obstacles.add(Obstacle(obsX, 0f, obsSpeed))
            }

            val obsIterator = obstacles.iterator()
            while (obsIterator.hasNext()) {
                val obs = obsIterator.next()
                obs.y += obs.speed * speedMultiplier

                // Ütközésvizsgálat
                val dist = Math.hypot((playerX - obs.x).toDouble(), (playerY - obs.y).toDouble())
                if (dist < playerRadius + obs.radius) {
                    if (activeShield) {
                        activeShield = false
                        createExplosion(obs.x, obs.y, Color.GREEN)
                        obsIterator.remove()
                    } else {
                        triggerGameOver()
                    }
                } else if (obs.y > screenHeight) {
                    obsIterator.remove()
                }
            }

            // Power-upok generálása és mozgása
            if (Math.random() < 0.008) {
                val type = PowerUpType.values().random()
                val pX = (Math.random() * (screenWidth - 100) + 50).toFloat()
                powerUps.add(PowerUp(pX, 0f, type))
            }

            val pIterator = powerUps.iterator()
            while (pIterator.hasNext()) {
                val p = pIterator.next()
                p.y += 8f * speedMultiplier

                val dist = Math.hypot((playerX - p.x).toDouble(), (playerY - p.y).toDouble())
                if (dist < playerRadius + 30) {
                    when (p.type) {
                        PowerUpType.SHIELD -> activeShield = true
                        PowerUpType.SLOW_MO -> slowMoTimer = 180
                        PowerUpType.DOUBLE_SCORE -> doubleScoreTimer = 240
                    }
                    createExplosion(p.x, p.y, Color.YELLOW)
                    pIterator.remove()
                } else if (p.y > screenHeight) {
                    pIterator.remove()
                }
            }

            // Effektek frissítése
            val ptIterator = particles.iterator()
            while (ptIterator.hasNext()) {
                val pt = ptIterator.next()
                pt.x += pt.vx
                pt.y += pt.vy
                pt.alpha -= 10
                if (pt.alpha <= 0) ptIterator.remove()
            }
        }

        private fun draw() {
            if (holder.surface.isValid) {
                val canvas = holder.lockCanvas() ?: return

                if (screenWidth == 0) {
                    screenWidth = canvas.width
                    screenHeight = canvas.height
                    playerX = screenWidth / 2f
                    playerY = screenHeight - 200f
                }

                // Háttér
                canvas.drawColor(Color.parseColor("#0B0C10"))

                // Játékos és Pajzs
                canvas.drawCircle(playerX, playerY, playerRadius, playerPaint)
                if (activeShield) {
                    canvas.drawCircle(playerX, playerY, playerRadius + 15f, shieldPaint)
                }

                // Akadályok
                for (obs in obstacles) {
                    canvas.drawCircle(obs.x, obs.y, obs.radius, obstaclePaint)
                }

                // Power-upok
                for (p in powerUps) {
                    val pPaint = Paint().apply { color = p.type.color; isAntiAlias = true }
                    canvas.drawCircle(p.x, p.y, 25f, pPaint)
                }

                // Részecskék
                for (pt in particles) {
                    val ptPaint = Paint().apply { color = pt.color; alpha = pt.alpha }
                    canvas.drawCircle(pt.x, pt.y, pt.size, ptPaint)
                }

                // HUD / Pontszám kijelzés
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("Pont: $score", 40f, 90f, textPaint)
                canvas.drawText("Rekord: $highScore", 40f, 160f, textPaint)

                if (isGameOver) {
                    val overPaint = Paint().apply {
                        color = Color.RED
                        textSize = 85f
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    canvas.drawText("GAME OVER", screenWidth / 2f, screenHeight / 2f, overPaint)
                    
                    val subPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = 45f
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText("Koppints az újrajátékhoz!", screenWidth / 2f, screenHeight / 2f + 120f, subPaint)
                }

                holder.unlockCanvasAndPost(canvas)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (isGameOver) {
                        resetGame()
                    } else {
                        // Képernyő bal oldala = balra mozgás, jobb oldala = jobbra mozgás
                        playerSpeedX = if (event.x < screenWidth / 2f) -20f else 20f
                    }
                }
                MotionEvent.ACTION_UP -> {
                    playerSpeedX = 0f
                }
            }
            return true
        }

        private fun triggerGameOver() {
            isGameOver = true
            createExplosion(playerX, playerY, Color.CYAN)
            if (score > highScore) {
                highScore = score
                saveHighScoreToSD(highScore)
            }
        }

        private fun resetGame() {
            score = 0
            obstacles.clear()
            powerUps.clear()
            particles.clear()
            activeShield = false
            slowMoTimer = 0
            doubleScoreTimer = 0
            playerX = screenWidth / 2f
            isGameOver = false
        }

        private fun createExplosion(x: Float, y: Float, color: Int) {
            for (i in 0..25) {
                particles.add(
                    Particle(
                        x, y,
                        (Math.random() * 24 - 12).toFloat(),
                        (Math.random() * 24 - 12).toFloat(),
                        (5..14).random().toFloat(),
                        color
                    )
                )
            }
        }

        // =========================================================================
        // SD KÁRTYA ÉS KÜLSŐ TÁRHELY ADATMENTÉS (JSON)
        // =========================================================================
        private fun saveHighScoreToSD(score: Int) {
            try {
                val file = File(context.getExternalFilesDir(null), "cyber_runner_stats.json")
                val json = JSONObject()
                json.put("highScore", score)
                val fos = FileOutputStream(file)
                fos.write(json.toString().toByteArray())
                fos.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun loadHighScoreFromSD() {
            try {
                val file = File(context.getExternalFilesDir(null), "cyber_runner_stats.json")
                if (file.exists()) {
                    val reader = FileReader(file)
                    val jsonString = reader.readText()
                    reader.close()
                    val json = JSONObject(jsonString)
                    highScore = json.optInt("highScore", 0)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun sleep() {
            try {
                Thread.sleep(16) // ~60 FPS
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun pause() {
            isPlaying = false
            try {
                thread?.join()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun resume() {
            isPlaying = true
            thread = Thread(this)
            thread?.start()
        }
    }

    // Segédosztályok
    data class Obstacle(var x: Float, var y: Float, val speed: Float, val radius: Float = 40f)
    data class PowerUp(var x: Float, var y: Float, val type: PowerUpType)
    data class Particle(var x: Float, var y: Float, val vx: Float, val vy: Float, val size: Float, val color: Int, var alpha: Int = 255)

    enum class PowerUpType(val color: Int) {
        SHIELD(Color.GREEN),
        SLOW_MO(Color.BLUE),
        DOUBLE_SCORE(Color.YELLOW)
    }
}
