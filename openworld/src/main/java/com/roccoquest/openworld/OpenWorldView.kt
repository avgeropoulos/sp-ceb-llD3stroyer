package com.roccoquest.openworld

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.*
import com.roccoquest.team3d.OpenWorld
import com.roccoquest.team3d.OpenWorldScene
import com.roccoquest.team3d.OpenWorldCombat
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

@SuppressLint("ViewConstructor")
class OpenWorldView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private val prefs=context.getSharedPreferences("open_world_v1",Context.MODE_PRIVATE)
    private val world=OpenWorld(prefs.getInt("stars",0),prefs.getLong("coins",0))
    private val combat=OpenWorldCombat(world).apply { select(OpenWorldCombat.Attack.entries.getOrNull(prefs.getInt("weapon",0)) ?: OpenWorldCombat.Attack.THROW) }
    private val scene=OpenWorldScene(world,combat)
    private val updater=AppUpdater(context)
    private var arsenal=false
    private val renderer=GlScene()
    private val hud=CanvasLayer()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var sw=1; private var sh=1; private var vw=960f
    private var last=0L; private var accumulator=0f
    private var title=true; private var paused=false
    private var touchX=0f; private var touchZ=0f; private var stickX=0f; private var stickZ=0f; private var orbit=0f
    private var joystick=-1; private var camera=-1; private var cameraX=0f
    private val held=HashSet<Int>()
    private var savedRevision=0
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8,8,8,8,16,0)
        setRenderer(this)
        renderMode=RENDERMODE_WHEN_DIRTY
        isFocusableInTouchMode=true
    }
    private var ticking=false
    private var frameTime=0L
    private val tick=object: Choreographer.FrameCallback {
        override fun doFrame(nanos:Long) {
            if(!ticking) return
            if(nanos-frameTime>=15_000_000L) { frameTime=nanos; requestRender() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun onResume() {
        super.onResume()
        if(!ticking) { ticking=true; frameTime=0L; Choreographer.getInstance().postFrameCallback(tick) }
    }
    override fun onPause() {
        ticking=false
        Choreographer.getInstance().removeFrameCallback(tick)
        super.onPause()
    }
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int) {
        super.onSizeChanged(w,h,ow,oh)
        if(w>0 && h>0) { val target=min(h,720); holder.setFixedSize((w.toLong()*target/h).toInt(),target) }
    }
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) { renderer.init(); hud.contextLost(); last=0L }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int) {
        sw=w; sh=max(1,h); vw=w*540f/sh
        GLES20.glViewport(0,0,w,h); hud.create(w,sh)
    }
    override fun onDrawFrame(gl:GL10?) {
        val now=System.nanoTime()
        val dt=if(last==0L) 0f else min(0.05f,(now-last)/1_000_000_000f)
        last=now
        if(!title && !paused && !arsenal) {
            accumulator+=dt
            while(accumulator>=1f/60) {
                val kx=(if(KeyEvent.KEYCODE_D in held || KeyEvent.KEYCODE_DPAD_RIGHT in held) 1f else 0f)-(if(KeyEvent.KEYCODE_A in held || KeyEvent.KEYCODE_DPAD_LEFT in held) 1f else 0f)
                val kz=(if(KeyEvent.KEYCODE_S in held || KeyEvent.KEYCODE_DPAD_DOWN in held) 1f else 0f)-(if(KeyEvent.KEYCODE_W in held || KeyEvent.KEYCODE_DPAD_UP in held) 1f else 0f)
                world.yaw+=orbit*0.035f
                world.update(1f/60,(touchX+stickX+kx).coerceIn(-1f,1f),(touchZ+stickZ+kz).coerceIn(-1f,1f))
                combat.update(1f/60)
                accumulator-=1f/60
            }
            if(savedRevision!=world.saveRevision) {
                prefs.edit().putInt("stars",world.starBits).putLong("coins",world.coinBits).apply()
                savedRevision=world.saveRevision
            }
        } else accumulator=0f
        GLES20.glClearColor(0.67f,0.86f,0.92f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        renderer.render(scene.build(sw.toFloat()/sh))
        hud.paint { c -> c.save(); c.scale(sh/540f,sh/540f); drawHud(c); c.restore() }
        hud.draw(true)
    }
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int=-1,center:Boolean=false) {
        paint.color=color; paint.textSize=size; paint.typeface=Typeface.create("sans-serif",Typeface.BOLD)
        paint.textAlign=if(center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(s,x,y,paint)
    }
    private fun panel(c:Canvas,x:Float,y:Float,w:Float,h:Float,color:Int=0xD9203F50.toInt()) {
        paint.color=color; c.drawRoundRect(x,y,x+w,y+h,18f,18f,paint)
    }
    private fun button(c:Canvas,label:String,x:Float,y:Float,r:Float,color:Int) {
        paint.color=color; c.drawCircle(x,y,r,paint); text(c,label,x,y+5,15f,-1,true)
    }
    private fun drawUpdate(c:Canvas) {
        panel(c,vw-210,18f,110f,45f,0xE6377284.toInt())
        text(c,if(UpdateStatus.busy.get()) "UPDATING" else "UPDATE",vw-155,47f,14f,-1,true)
        if(title || paused) text(c,UpdateStatus.text,vw/2,78f,13f,-1,true)
    }
    private fun drawHud(c:Canvas) {
        if(arsenal) {
            paint.color=0xEE14374C.toInt(); c.drawRect(0f,0f,vw,540f,paint)
            text(c,"ATTACKS & POWERS",vw/2,105f,30f,0xFFFFE397.toInt(),true)
            text(c,"Choose a power, then use ATTACK. Powers recharge automatically.",vw/2,142f,15f,-1,true)
            val names=OpenWorldCombat.Attack.entries.map { it.label }+"Long Jump"
            for(i in names.indices) {
                val x=vw/2-270+(i%4)*138; val y=190f+(i/4)*110
                panel(c,x,y,126f,90f,if(i==combat.selected.ordinal) 0xFF427F90.toInt() else 0xFF2C5065.toInt())
                text(c,names[i],x+63,y+38,15f,-1,true)
                text(c,if(i<7) "${OpenWorldCombat.Attack.entries[i].cooldown}s recharge" else "Leap forward",x+63,y+65,12f,0xFFFFE397.toInt(),true)
            }
            text(c,"Tap below to return • Game paused while choosing",vw/2,460f,15f,-1,true)
            return
        }
        if(title || paused) {
            paint.color=0xBB14374C.toInt(); c.drawRect(0f,0f,vw,540f,paint)
            text(c,if(title) "TEAM UP" else "TAKE A BREATHER",vw/2,120f,48f,0xFFFFE397.toInt(),true)
            text(c,"OPEN WORLD",vw/2,158f,24f,-1,true)
            text(c,"Adventure Island • Rocco & Blocky",vw/2,202f,18f,-1,true)
            text(c,"Explore freely. Find 6 Team Stars. Follow the coin trails.",vw/2,246f,16f,-1,true)
            text(c,"Left stick: move   •   Drag right side: camera",vw/2,278f,16f,-1,true)
            text(c,"ATTACK fights. POWERS selects weapons. TEAM boosts your jump.",vw/2,305f,16f,-1,true)
            panel(c,vw/2-140,335f,280f,62f,0xFFE5AE55.toInt())
            text(c,if(title) "LET'S EXPLORE" else "KEEP EXPLORING",vw/2,374f,20f,0xFF17394C.toInt(),true)
            if(paused) { panel(c,vw/2-140,412f,280f,48f); text(c,"RETURN TO MEADOW",vw/2,442f,16f,-1,true) }
            else text(c,"Standalone adventure • Progress saves automatically",vw/2,440f,14f,-1,true)
            text(c,"Controller: B attack · L1 weapon · A jump · Y boost · X swap",vw/2,493f,13f,-1,true)
            drawUpdate(c)
            return
        }
        panel(c,22f,18f,220f,64f)
        text(c,"TEAM STARS  ${world.starCount} / 6",38f,44f,18f,0xFFFFE397.toInt())
        text(c,"COINS  ${world.coinCount} / 40",38f,67f,14f)
        text(c,"HEALTH ${combat.health}/6  •  WINS ${combat.defeated}",38f,151f,13f,0xFF17394C.toInt())
        text(c,world.region,vw/2,66f,14f,0xFF17394C.toInt(),true)
        drawUpdate(c)
        panel(c,vw-92,18f,72f,45f); text(c,"PAUSE",vw-56,47f,14f,-1,true)
        if(world.noticeTime>0) { panel(c,vw/2-255,89f,510f,43f); text(c,world.notice,vw/2,117f,14f,-1,true) }
        else if(world.starCount==5 && world.coinCount<20) text(c,"Find 20 coins to reveal the meadow star",vw/2,108f,16f,0xFF17394C.toInt(),true)
        paint.color=0x66314956; c.drawCircle(105f,437f,69f,paint)
        paint.color=0xBBFFFFFF.toInt(); c.drawCircle(105f+touchX*45,437f+touchZ*45,29f,paint)
        text(c,"MOVE",105f,527f,13f,0xFF17394C.toInt(),true)
        button(c,"JUMP",vw-72,450f,48f,0xE6E59A50.toInt())
        button(c,if(combat.remaining>0) "${ceil(combat.remaining).toInt()}s" else "ATTACK",vw-184,404f,46f,0xE6C85E58.toInt())
        text(c,combat.selected.label,vw-184,467f,13f,0xFF17394C.toInt(),true)
        button(c,if(world.boostCooldown>0) "WAIT" else "TEAM",vw-290,470f,35f,0xE65C9AB9.toInt())
        button(c,"LEAP",vw-290,358f,32f,0xCC577D74.toInt())
        button(c,"SWAP",vw-70,320f,33f,0xCC577D74.toInt())
        button(c,"POWERS",vw-190,294f,36f,0xE65B5184.toInt())
        text(c,if(world.blocky) "BLOCKY" else "ROCCO",vw/2,515f,15f,0xFF17394C.toInt(),true)
        // Mini-map with north fixed, showing remaining stars and the player.
        val mx=vw-103; val my=125f
        panel(c,mx-48,my-48,96f,96f,0xAA203F50.toInt())
        paint.color=0xFFFFDC75.toInt()
        for((i,s) in world.stars.withIndex()) if(world.starBits and (1 shl i)==0 && (i!=5 || world.coinCount>=20)) c.drawCircle(mx+s.x*1.2f,my+s.z*1.2f,3.2f,paint)
        paint.color=-1; c.drawCircle(mx+world.x*1.2f,my+world.z*1.2f,4f,paint)
    }
    fun pauseGame() { queueEvent { if(!title) paused=true; arsenal=false; clearInput(); last=0L } }
    private fun clearInput() { touchX=0f; touchZ=0f; stickX=0f; stickZ=0f; orbit=0f; joystick=-1; camera=-1; held.clear() }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e:MotionEvent):Boolean {
        val scale=540f/max(1,height); val action=e.actionMasked; val id=e.getPointerId(e.actionIndex)
        val points=Array(e.pointerCount) { i -> floatArrayOf(e.getPointerId(i).toFloat(),e.getX(i)*scale,e.getY(i)*scale) }
        queueEvent {
            if(action==MotionEvent.ACTION_CANCEL) { clearInput(); return@queueEvent }
            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
                val p=points.first { it[0].toInt()==id }; val x=p[1]; val y=p[2]
                if(!arsenal && x in vw-210..vw-100 && y in 18f..63f) { if(!title) paused=true; clearInput(); updater.start() }
                else if(arsenal) {
                    val col=((x-(vw/2-270))/138).toInt(); val row=((y-190)/110).toInt()
                    if(x>=vw/2-270 && col in 0..3 && y>=190 && row in 0..1 && x<vw/2-270+col*138+126 && y<190+row*110+90) {
                        val choice=row*4+col
                        if(choice<7) { combat.select(OpenWorldCombat.Attack.entries[choice]); prefs.edit().putInt("weapon",choice).apply() } else world.longJump()
                        arsenal=false; clearInput()
                    } else if(y>430) { arsenal=false; clearInput() }
                }
                else if(title || paused) {
                    if(x in vw/2-140..vw/2+140 && y in 335f..397f) { title=false; paused=false; clearInput() }
                    else if(paused && x in vw/2-140..vw/2+140 && y in 412f..460f) { world.home(); combat.heal(); paused=false; clearInput() }
                } else when {
                    x>vw-100 && y<75 -> { paused=true; clearInput() }
                    hypot(x-(vw-72),y-450)<54 -> world.jump()
                    hypot(x-(vw-184),y-404)<49 -> combat.attack()
                    hypot(x-(vw-290),y-470)<40 -> world.boost()
                    hypot(x-(vw-290),y-358)<37 -> world.longJump()
                    hypot(x-(vw-70),y-320)<37 -> world.swap()
                    hypot(x-(vw-190),y-294)<40 -> { arsenal=true; clearInput() }
                    x<210 && y>320 && joystick<0 -> joystick=id
                    x>vw/2 && camera<0 -> { camera=id; cameraX=x }
                }
            }
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) {
                if(joystick==id) { joystick=-1; touchX=0f; touchZ=0f }
                if(camera==id) camera=-1
            }
            if(!title && !paused && !arsenal) for(p in points) {
                if(p[0].toInt()==joystick) { touchX=((p[1]-105)/55).coerceIn(-1f,1f); touchZ=((p[2]-437)/55).coerceIn(-1f,1f); if(abs(touchX)<0.12) touchX=0f; if(abs(touchZ)<0.12) touchZ=0f }
                if(p[0].toInt()==camera) { world.yaw-=(p[1]-cameraX)*0.009f; cameraX=p[1] }
            }
        }
        return true
    }
    fun key(e:KeyEvent):Boolean {
        val k=e.keyCode
        val accepted=k in intArrayOf(KeyEvent.KEYCODE_W,KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_D,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_SPACE,KeyEvent.KEYCODE_E,KeyEvent.KEYCODE_Q,KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_Y,KeyEvent.KEYCODE_BUTTON_X,KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_F,KeyEvent.KEYCODE_R,KeyEvent.KEYCODE_T,KeyEvent.KEYCODE_G,KeyEvent.KEYCODE_B,KeyEvent.KEYCODE_P,KeyEvent.KEYCODE_L,KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BUTTON_L1,KeyEvent.KEYCODE_BUTTON_L2,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.KEYCODE_BUTTON_R2)
        if(!accepted) return false
        val down=e.action==KeyEvent.ACTION_DOWN; val fresh=down && e.repeatCount==0
        queueEvent {
            if(down) held.add(k) else held.remove(k)
            if(fresh) {
                if(title) { title=false; clearInput() }
                else if(k==KeyEvent.KEYCODE_ESCAPE || k==KeyEvent.KEYCODE_BUTTON_START) { if(arsenal) arsenal=false else paused=!paused; clearInput() }
                else if(!paused && !arsenal) when(k) {
                    KeyEvent.KEYCODE_SPACE,KeyEvent.KEYCODE_BUTTON_A -> world.jump()
                    KeyEvent.KEYCODE_E,KeyEvent.KEYCODE_BUTTON_Y -> world.boost()
                    KeyEvent.KEYCODE_Q,KeyEvent.KEYCODE_BUTTON_X -> world.swap()
                    KeyEvent.KEYCODE_F,KeyEvent.KEYCODE_BUTTON_B -> combat.attack()
                    KeyEvent.KEYCODE_R,KeyEvent.KEYCODE_BUTTON_L1 -> { combat.next(); prefs.edit().putInt("weapon",combat.selected.ordinal).apply() }
                    KeyEvent.KEYCODE_T -> combat.attack(OpenWorldCombat.Attack.TNT)
                    KeyEvent.KEYCODE_G -> combat.attack(OpenWorldCombat.Attack.SUPER)
                    KeyEvent.KEYCODE_B,KeyEvent.KEYCODE_BUTTON_L2 -> combat.attack(OpenWorldCombat.Attack.BLACK_FLOWER)
                    KeyEvent.KEYCODE_P,KeyEvent.KEYCODE_BUTTON_R2 -> combat.attack(OpenWorldCombat.Attack.POSEIDON)
                    KeyEvent.KEYCODE_L,KeyEvent.KEYCODE_BUTTON_R1 -> world.longJump()
                }
            }
        }
        return true
    }
    fun pad(e:MotionEvent):Boolean {
        if(e.source and InputDevice.SOURCE_JOYSTICK!=InputDevice.SOURCE_JOYSTICK) return false
        fun axis(a:Int):Float { val v=e.getAxisValue(a); return if(abs(v)>0.18f) v else 0f }
        val x=axis(MotionEvent.AXIS_X); val z=axis(MotionEvent.AXIS_Y); val r=axis(MotionEvent.AXIS_Z)
        queueEvent { if(!title && !paused && !arsenal) { stickX=x; stickZ=z; orbit=r } }
        return true
    }
}
