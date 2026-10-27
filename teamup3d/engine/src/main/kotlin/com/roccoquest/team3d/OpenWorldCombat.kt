package com.roccoquest.team3d

import kotlin.math.*

/** Combat belongs only to the standalone adventure. Powers use cooldowns, not classic inventory. */
class OpenWorldCombat(private val world: OpenWorld) {
    enum class Attack(val label: String, val cooldown: Float, val color: Int) {
        THROW("Throw",0.4f,0xFFFFA34E.toInt()), SWORD("Sword",0.5f,0xFFDBF3FF.toInt()),
        BOW("Bow",0.55f,0xFFFFDE85.toInt()), TNT("TNT",3f,0xFFEF705A.toInt()),
        SUPER("Super",7f,0xFFE59EFF.toInt()), BLACK_FLOWER("Black Flower",4f,0xFF9F74DF.toInt()),
        POSEIDON("Poseidon",10f,0xFF75E5FF.toInt())
    }
    data class Enemy(val homeX: Float,val homeZ: Float,val boss: Boolean=false) {
        var x=homeX; var z=homeZ; var y=0f
        val maxHp=if(boss) 16 else 4
        var hp=maxHp; var hurt=0f; var respawn=0f
    }
    data class Shot(var x:Float,var y:Float,var z:Float,val dx:Float,var dy:Float,val dz:Float,val kind:Attack,var life:Float)
    data class Effect(val x:Float,val y:Float,val z:Float,val radius:Float,val color:Int,var life:Float=0.45f)
    val enemies=mutableListOf(Enemy(-10f,5f),Enemy(10f,-8f),Enemy(-16f,15f),Enemy(20f,19f),Enemy(-9f,-15f),Enemy(8f,-25f),Enemy(-23f,-7f),Enemy(0f,-28f,true))
    val shots=mutableListOf<Shot>()
    val effects=mutableListOf<Effect>()
    private val cooldowns=FloatArray(Attack.entries.size)
    var selected=Attack.THROW; private set
    var health=6; private set
    var invulnerable=0f; private set
    var defeated=0; private set
    var swing=0f; private set
    val remaining get()=cooldowns[selected.ordinal]
    fun select(attack:Attack) { selected=attack }
    fun next() { selected=Attack.entries[(selected.ordinal+1)%Attack.entries.size]; world.say("${selected.label} ready — ATTACK to use") }
    fun heal() { health=6; invulnerable=2f; shots.clear(); effects.clear() }
    private fun hit(e:Enemy,damage:Int) {
        if(e.hp<=0) return
        e.hp=max(0,e.hp-damage); e.hurt=0.18f
        if(e.hp==0) { defeated++; e.respawn=if(e.boss) 60f else 25f; world.say(if(e.boss) "KING KRAG defeated!" else "Enemy defeated! $defeated victories") }
    }
    private fun blast(x:Float,y:Float,z:Float,radius:Float,damage:Int,color:Int) {
        effects+=Effect(x,y,z,radius,color)
        for(e in enemies) if(e.hp>0 && hypot(e.x-x,e.z-z)<radius && abs(e.y+0.8f-y)<radius) hit(e,damage)
    }
    fun attack(kind:Attack=selected):Boolean {
        if(cooldowns[kind.ordinal]>0) return false
        cooldowns[kind.ordinal]=kind.cooldown
        // Assist aiming at the closest visible-height enemy near the direction of travel.
        val sx=sin(world.facing); val sz=cos(world.facing)
        val target=enemies.filter { it.hp>0 && hypot(it.x-world.x,it.z-world.z)<19f && abs(it.y-world.y)<4f &&
            ((it.x-world.x)*sx+(it.z-world.z)*sz)> -1f }.minByOrNull { hypot(it.x-world.x,it.z-world.z) }
        var dx=sx; var dz=sz
        if(target!=null) { val length=max(0.01f,hypot(target.x-world.x,target.z-world.z)); dx=(target.x-world.x)/length; dz=(target.z-world.z)/length }
        when(kind) {
            Attack.SWORD -> {
                swing=0.22f
                effects+=Effect(world.x+dx*1.4f,world.y+1f,world.z+dz*1.4f,1.4f,kind.color,0.2f)
                for(e in enemies) if(e.hp>0 && hypot(e.x-world.x,e.z-world.z)<3.3f && abs(e.y-world.y)<2f && (e.x-world.x)*dx+(e.z-world.z)*dz> -0.3f) hit(e,3)
            }
            Attack.SUPER -> blast(world.x,world.y+1f,world.z,6f,4,kind.color)
            Attack.POSEIDON -> blast(world.x,world.y+1f,world.z,10f,7,kind.color)
            else -> {
                val speed=when(kind) { Attack.BOW->24f; Attack.TNT->8f; else->15f }
                shots+=Shot(world.x,world.y+1f,world.z,dx*speed,if(kind==Attack.TNT) 6f else 0f,dz*speed,kind,if(kind==Attack.TNT) 1.15f else 1.3f)
            }
        }
        return true
    }
    fun update(dt:Float) {
        for(i in cooldowns.indices) cooldowns[i]=max(0f,cooldowns[i]-dt)
        invulnerable=max(0f,invulnerable-dt); swing=max(0f,swing-dt)
        effects.forEach { it.life-=dt }; effects.removeAll { it.life<=0 }
        for(e in enemies) {
            e.hurt=max(0f,e.hurt-dt)
            if(e.hp<=0) {
                e.respawn-=dt
                if(e.respawn<=0 && hypot(world.x-e.homeX,world.z-e.homeZ)>8f) { e.hp=e.maxHp; e.x=e.homeX; e.z=e.homeZ }
                continue
            }
            e.y=world.floorAt(e.x,e.z)
            val distance=hypot(world.x-e.x,world.z-e.z)
            // The central checkpoint is safe. Enemies stay in their own territory and elevation.
            if(distance<10f && distance>0.2f && hypot(world.x,world.z-8f)>4f && abs(e.y-world.y)<2f) {
                val speed=if(e.boss) 1.5f else 2.2f
                val nx=e.x+(world.x-e.x)/distance*speed*dt; val nz=e.z+(world.z-e.z)/distance*speed*dt
                if(hypot(nx-e.homeX,nz-e.homeZ)<7f && abs(world.floorAt(nx,nz)-e.y)<0.6f) { e.x=nx; e.z=nz }
            }
            if(distance<(if(e.boss) 1.6f else 1.05f) && abs(world.y-e.y)<1.8f && invulnerable<=0 && hypot(world.x,world.z-8f)>4f) {
                health--; invulnerable=1.6f
                world.say("Ouch! Attack or jump away — $health health")
                if(health<=0) { world.home(); heal(); world.say("Back to safety! Your stars and coins are kept.") }
            }
        }
        val it=shots.iterator()
        while(it.hasNext()) {
            val s=it.next(); s.life-=dt; s.x+=s.dx*dt; s.z+=s.dz*dt; s.y+=s.dy*dt
            if(s.kind==Attack.TNT) { s.dy-=14f*dt; val floor=world.floorAt(s.x,s.z,s.y+0.2f); if(s.y<floor+0.15f) { s.y=floor+0.15f; s.dy=0f } }
            var consumed=world.solidAt(s.x,s.y,s.z)
            if(!consumed && s.kind!=Attack.TNT) for(e in enemies) if(e.hp>0 && hypot(e.x-s.x,e.z-s.z)<(if(e.boss) 1.4f else 0.85f) && abs(e.y+0.8f-s.y)<1.5f) {
                hit(e,if(s.kind==Attack.BLACK_FLOWER) 5 else if(s.kind==Attack.BOW) 3 else 2)
                effects+=Effect(s.x,s.y,s.z,0.7f,s.kind.color,0.2f); consumed=true; break
            }
            if(s.life<=0 || consumed) {
                if(s.kind==Attack.TNT) blast(s.x,s.y,s.z,4.5f,6,s.kind.color)
                it.remove()
            }
        }
    }
}
