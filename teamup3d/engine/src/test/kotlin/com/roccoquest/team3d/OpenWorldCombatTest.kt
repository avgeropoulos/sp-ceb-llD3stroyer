package com.roccoquest.team3d

import kotlin.test.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class OpenWorldCombatTest {
    private val dt=1f/60
    private fun battle(distance:Float=2f):Pair<OpenWorld,OpenWorldCombat> {
        val w=OpenWorld(); w.x= -12f; w.z=12f
        val b=OpenWorldCombat(w); b.enemies.clear(); b.enemies+=OpenWorldCombat.Enemy(-12f,12f+distance)
        return w to b
    }
    @Test fun everyAttackDamagesEnemies() {
        for(a in OpenWorldCombat.Attack.entries) {
            val (_,b)=battle(if(a==OpenWorldCombat.Attack.TNT) 8f else 2f)
            assertTrue(b.attack(a),a.label)
            repeat(100) { b.update(dt) }
            assertTrue(b.enemies[0].hp<4,"${a.label} must cause damage")
        }
    }
    @Test fun cooldownRejectsSpamAndThenRecharges() {
        val (_,b)=battle(); b.select(OpenWorldCombat.Attack.BOW)
        assertTrue(b.attack()); assertFalse(b.attack()); assertEquals(1,b.shots.size)
        repeat(40) { b.update(dt) }; assertTrue(b.attack())
    }
    @Test fun rangedShotsDoNotHitBehindPlayer() {
        val (_,b)=battle(-4f); b.attack(OpenWorldCombat.Attack.BOW)
        repeat(25) { b.update(dt) }; assertEquals(4,b.enemies[0].hp)
    }
    @Test fun swordCannotHitFarAway() {
        val (_,b)=battle(6f); b.attack(OpenWorldCombat.Attack.SWORD); assertEquals(4,b.enemies[0].hp)
    }
    @Test fun strongAttackCountsKillOnceAndEffectsExpire() {
        val (_,b)=battle(); b.attack(OpenWorldCombat.Attack.POSEIDON)
        assertEquals(1,b.defeated); b.attack(OpenWorldCombat.Attack.SUPER); assertEquals(1,b.defeated)
        repeat(100) { b.update(dt) }; assertTrue(b.effects.isEmpty()); assertTrue(b.shots.isEmpty())
    }
    @Test fun damageHasInvulnerabilityAndDeathKeepsCollectibles() {
        val w=OpenWorld(3,7); w.x= -12f; w.z=12f
        val b=OpenWorldCombat(w); b.enemies.clear(); b.enemies+=OpenWorldCombat.Enemy(w.x,w.z)
        b.update(dt); assertEquals(5,b.health); repeat(10) { b.update(dt) }; assertEquals(5,b.health)
        repeat(650) { b.update(dt) }
        assertEquals(6,b.health); assertEquals(0f,w.x); assertEquals(8f,w.z)
        assertEquals(3,w.starBits); assertEquals(7L,w.coinBits)
    }
    @Test fun checkpointIsSafe() {
        val w=OpenWorld(); val b=OpenWorldCombat(w); b.enemies.clear(); b.enemies+=OpenWorldCombat.Enemy(w.x,w.z)
        repeat(180) { b.update(dt) }; assertEquals(6,b.health)
    }
    @Test fun longJumpTravelsEvenWithoutHoldingTheStick() {
        val w=OpenWorld(); w.longJump(); repeat(40) { w.update(dt,0f,0f) }
        assertTrue(w.z>16f); assertTrue(w.y>0)
    }
    @Test fun projectilesStopAtTerrain() {
        val w=OpenWorld(); w.z= -31f
        val b=OpenWorldCombat(w); b.enemies.clear(); b.enemies+=OpenWorldCombat.Enemy(0f,-25f)
        b.attack(OpenWorldCombat.Attack.BOW); repeat(30) { b.update(dt) }
        assertTrue(b.shots.isEmpty()); assertEquals(4,b.enemies[0].hp)
    }
    @Test fun combatSceneRenders() {
        val (w,b)=battle(); b.update(dt); b.attack(OpenWorldCombat.Attack.SUPER)
        val scene=OpenWorldScene(w,b); val sky=BufferedImage(960,540,BufferedImage.TYPE_INT_ARGB)
        val g=sky.createGraphics(); g.color=java.awt.Color(171,219,235);g.fillRect(0,0,960,540);g.dispose()
        val frame=scene.build(16f/9);assertTrue(frame.count>0)
        val dir=File("build/screens3d").apply {mkdirs()}
        ImageIO.write(SoftRaster(960,540).render(frame,sky),"png",File(dir,"open-world-combat.png"))
    }
}
