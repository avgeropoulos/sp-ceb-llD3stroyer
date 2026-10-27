package com.roccoquest.team3d

import kotlin.math.*

/** Standalone free-roaming prototype. Coordinates are metres, Y is up. No classic save keys. */
class OpenWorld(savedStars: Int = 0, savedCoins: Long = 0L) {
    data class Platform(val x: Float, val z: Float, val w: Float, val d: Float, val top: Float, val color: Int)
    data class Pickup(val x: Float, val y: Float, val z: Float, val name: String = "")
    val platforms = mutableListOf<Platform>()
    val stars = mutableListOf<Pickup>()
    val coins = mutableListOf<Pickup>()
    var starBits = savedStars and 63; private set
    var coinBits = savedCoins and ((1L shl 40) - 1); private set
    val starCount get() = Integer.bitCount(starBits)
    val coinCount get() = java.lang.Long.bitCount(coinBits)
    var x = 0f; var y = 0f; var z = 8f
    var vy = 0f; private set
    var yaw = 0f
    var facing = 0f; private set
    var grounded = true; private set
    var blocky = false; private set
    var buddyX = -2f; private set
    var buddyZ = 9f; private set
    var time = 0f; private set
    var notice = "Find six Team Stars. Start at the gold trail!"; private set
    var noticeTime = 8f; private set
    var checkpointX = 0f; private set
    var checkpointZ = 8f; private set
    private var coyote = 0.1f
    private var jumpBuffer = 0f
    private var boostReady = true
    private var boostRequested = false
    private var dashTime=0f
    fun longJump() { if(grounded && !inWater) { vy=9f; grounded=false; coyote=0f; dashTime=0.65f; say("LONG JUMP! Hold a direction to leap forward.") } }
    var boostCooldown = 0f; private set
    var saveRevision = 0; private set
    val won get() = starCount == 6
    val inWater get() = x > 14 && x < 30 && z > -6 && z < 14 && y < 0.1f
    val region get() = when { x < -14 -> "STARLIGHT STEPS"; z < -13 -> "SUMMIT TRAIL"; x > 14 -> "SUNSHINE LAGOON"; else -> "TEAM UP MEADOW" }

    init {
        fun platform(x: Float, z: Float, w: Float, d: Float, h: Float, color: Long = 0xFF77B96A) {
            platforms += Platform(x,z,w,d,h,color.toInt())
        }
        // Broad, overlapping terraces; every rise is below the ordinary jump height.
        platform(0f,-18f,16f,10f,2f)
        platform(0f,-22f,12f,8f,4f)
        platform(0f,-25f,8f,6f,6f)
        platform(0f,-28f,5f,4f,8f)
        // Floating path. The last rise requires the midair team boost.
        for (i in 0..4) platform(-15f-i*3.5f, -3f-i*4f,4f,4f,1.5f+i*1.6f,0xFF9187D0)
        platform(-29f,-23f,5f,5f,12f,0xFFA9A0E2)
        // Lagoon stepping stones.
        platform(18f,4f,3f,3f,1f,0xFFFFD28B)
        platform(22f,1f,3f,3f,2f,0xFFFFD28B)
        platform(26f,-2f,4f,4f,3f,0xFFFFD28B)
        // Home and climbable roof, with a staircase.
        platform(9f,18f,7f,6f,3.2f,0xFFFFC778)
        for(i in 0..3) platform(4.5f,13f+i*1.5f,2f,1.5f,(i+1)*0.8f,0xFFB98859)
        stars += Pickup(0f,9.2f,-28f,"Summit explorer")
        stars += Pickup(26f,4.2f,-2f,"Lagoon leap")
        stars += Pickup(-29f,13.2f,-23f,"Teamwork in the sky")
        stars += Pickup(9f,4.4f,18f,"Home sweet home")
        stars += Pickup(-19f,1.2f,20f,"Meadow hideaway")
        stars += Pickup(0f,1.4f,4f,"Coin trail champion")
        for(i in 0..19) {
            val a = i*PI.toFloat()/10
            coins += Pickup(cos(a)*11f,1f,sin(a)*11f)
        }
        for(i in 0..9) coins += Pickup(-6f+i*1.3f,1f,15f)
        for(i in 0..9) coins += Pickup(17f+i%5*2.5f,0.4f,7f+i/5*3f)
    }
    fun jump() { jumpBuffer = 0.16f }
    fun boost() { boostRequested = true }
    fun swap() { blocky = !blocky; say(if(blocky) "Blocky takes the lead!" else "Rocco takes the lead!") }
    fun home() { dashTime=0f; jumpBuffer=0f; boostRequested=false; x=checkpointX; z=checkpointZ; y=0f; vy=0f; grounded=true; boostReady=true; say("Back at your meadow checkpoint") }
    fun say(s: String) { notice=s; noticeTime=4f }
    private fun baseHeight(px: Float,pz: Float) = if(px>14 && px<30 && pz> -6 && pz<14) -1.2f else 0f
    fun floorAt(px: Float,pz: Float, ceiling: Float = Float.POSITIVE_INFINITY): Float {
        var h = baseHeight(px,pz)
        for(p in platforms) if(abs(px-p.x)<p.w/2+0.28f && abs(pz-p.z)<p.d/2+0.28f && p.top<=ceiling) h=max(h,p.top)
        return h
    }
    fun solidAt(px:Float,py:Float,pz:Float):Boolean {
        if(py<baseHeight(px,pz)) return true
        return platforms.withIndex().any { (i,p) ->
            val bottom=if(i<4 || p.x==9f || p.x==4.5f) 0f else p.top-0.7f
            abs(px-p.x)<p.w/2 && abs(pz-p.z)<p.d/2 && py>bottom && py<p.top
        }
    }
    private fun blocked(px: Float,pz: Float): Boolean = platforms.any {
        abs(px-it.x)<it.w/2+0.32f && abs(pz-it.z)<it.d/2+0.32f && y<it.top-0.12f && y+1.7f>it.top-0.7f
    } || platforms.take(4).any {
        abs(px-it.x)<it.w/2+0.32f && abs(pz-it.z)<it.d/2+0.32f && y<it.top-0.12f
    } || platforms.any {
        it.x==9f && abs(px-it.x)<it.w/2+0.32f && abs(pz-it.z)<it.d/2+0.32f && y<it.top-0.12f
    }
    /** Caller uses a fixed 60 Hz step. Axes are camera-relative, forward is negative Z. */
    fun update(dt: Float, inputX: Float, inputZ: Float) {
        dashTime=max(0f,dashTime-dt)
        time+=dt; noticeTime=max(0f,noticeTime-dt); boostCooldown=max(0f,boostCooldown-dt)
        jumpBuffer=max(0f,jumpBuffer-dt)
        if(grounded) { coyote=0.12f; boostReady=true } else coyote=max(0f,coyote-dt)
        if(jumpBuffer>0 && (coyote>0 || inWater)) { vy=if(inWater) 8f else 10.5f; grounded=false; coyote=0f; jumpBuffer=0f }
        if(boostRequested) {
            if(boostReady && boostCooldown<=0) { vy=12f; grounded=false; coyote=0f; boostReady=false; boostCooldown=1.4f; say("TEAM BOOST!") }
            boostRequested=false
        }
        val len=max(1f,sqrt(inputX*inputX+inputZ*inputZ))
        val speed=if(inWater) 4f else if(dashTime>0) 14f else 7.5f
        val dx=if(dashTime>0 && abs(inputX)+abs(inputZ)<0.1f) sin(facing)*speed*dt else (cos(yaw)*inputX+sin(yaw)*inputZ)/len*speed*dt
        val dz=if(dashTime>0 && abs(inputX)+abs(inputZ)<0.1f) cos(facing)*speed*dt else (-sin(yaw)*inputX+cos(yaw)*inputZ)/len*speed*dt
        if(abs(dx)+abs(dz)>0.0001f) facing=atan2(dx,dz)
        if(!blocked(x+dx,z)) x+=dx
        if(!blocked(x,z+dz)) z+=dz
        val oldY=y
        vy-=if(inWater) 9f*dt else 23f*dt
        if(inWater) vy=max(vy,-2f)
        y+=vy*dt
        val floor=floorAt(x,z,oldY+0.12f)
        if(y<=floor && vy<=0) { y=floor; vy=0f; grounded=true } else grounded=false
        if(inWater && y< -0.65f) { y= -0.65f; vy=0f }
        if(abs(x)>35 || abs(z)>35 || y< -8) home()
        buddyX+=(x-sin(facing)*2f-buddyX)*min(1f,dt*5)
        buddyZ+=(z-cos(facing)*2f-buddyZ)*min(1f,dt*5)
        for((i,c) in coins.withIndex()) if(coinBits and (1L shl i)==0L && distance(c)<1.4f) { coinBits=coinBits or (1L shl i); saveRevision++; if(coinCount==20) say("20 coins! The meadow Team Star is ready.") }
        for((i,s) in stars.withIndex()) if(starBits and (1 shl i)==0 && (i!=5 || coinCount>=20) && distance(s)<1.6f) { starBits=starBits or (1 shl i); saveRevision++; say(if(won) "All six stars! Adventure Island complete!" else "TEAM STAR: ${s.name}") }
    }
    private fun distance(p: Pickup) = sqrt((x-p.x).pow(2)+(y+0.8f-p.y).pow(2)+(z-p.z).pow(2))
}
