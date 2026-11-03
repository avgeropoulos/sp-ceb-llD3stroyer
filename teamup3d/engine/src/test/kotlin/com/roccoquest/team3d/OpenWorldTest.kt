package com.roccoquest.team3d

import kotlin.test.*
import kotlin.math.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class OpenWorldTest {
    private val dt=1f/60
    @Test fun movementIsFreeRoamingAndCameraRelative() {
        val w=OpenWorld(); w.update(dt,1f,0f); assertTrue(w.x>0); assertEquals(8f,w.z)
        w.yaw=PI.toFloat()/2; val x=w.x; w.update(dt,0f,-1f); assertTrue(w.x<x)
    }
    @Test fun diagonalDoesNotRunFaster() {
        val a=OpenWorld(); val b=OpenWorld()
        a.update(dt,1f,0f); b.update(dt,1f,1f)
        assertEquals(a.x,hypot(b.x,b.z-8f),0.0001f)
    }
    @Test fun ordinaryJumpClearsTwoMetreTerraces() {
        val w=OpenWorld(); w.z= -12f; w.jump()
        repeat(28) { w.update(dt,0f,-1f) }
        repeat(80) { w.update(dt,0f,0f) }
        assertEquals(2f,w.y,0.001f); assertTrue(w.grounded)
    }
    @Test fun cannotWalkThroughTheSummit() {
        val w=OpenWorld(); w.z= -12f
        repeat(90) { w.update(dt,0f,-1f) }
        assertTrue(w.z> -13f); assertEquals(0f,w.y)
    }
    @Test fun jumpAndTeamBoostReachHighPlatforms() {
        val w=OpenWorld(); w.jump(); repeat(22) { w.update(dt,0f,0f) }; w.boost()
        var highest=0f
        repeat(100) { w.update(dt,0f,0f); highest=max(highest,w.y) }
        assertTrue(highest>5f); assertTrue(w.grounded)
    }
    @Test fun lagoonIsSwimmableAndCanBeExited() {
        val w=OpenWorld(); w.x=20f; w.z=8f
        repeat(200) { w.update(dt,0f,0f) }
        assertTrue(w.inWater); assertTrue(w.y>= -0.7f)
        w.jump(); repeat(90) { w.update(dt,-1f,0f) }
        assertTrue(w.x<14f); assertEquals(0f,w.y,0.01f)
    }
    @Test fun pickupsDoNotDuplicateAndRestore() {
        val w=OpenWorld(); val c=w.coins[0]; w.x=c.x; w.y=c.y-0.8f; w.z=c.z
        repeat(3) { w.update(dt,0f,0f) }
        assertEquals(1,w.coinCount)
        val restored=OpenWorld(w.starBits,w.coinBits)
        assertEquals(1,restored.coinCount); assertEquals(0,restored.starCount)
    }
    @Test fun meadowStarNeedsTwentyCoinsAndSixStarsWin() {
        val locked=OpenWorld(); locked.z=4f; locked.update(dt,0f,0f); assertEquals(0,locked.starCount)
        val w=OpenWorld(31,(1L shl 20)-1); w.z=4f; w.update(dt,0f,0f)
        assertEquals(6,w.starCount); assertTrue(w.won)
    }
    @Test fun boundaryReturnsSafelyToMeadow() {
        val w=OpenWorld(3,7); w.x=36f; w.update(dt,0f,0f)
        assertEquals(0f,w.x); assertEquals(8f,w.z); assertEquals(0f,w.y)
        assertEquals(2,w.starCount); assertEquals(3,w.coinCount)
    }
    @Test fun renderUsesFiniteCameraAndReusableMeshes() {
        val w=OpenWorld(); val s=OpenWorldScene(w); val first=s.build(16f/9)
        assertTrue(first.count>40); assertTrue(first.viewProj.m.all { it.isFinite() })
        val mesh=first.cmd(0).mesh
        repeat(20) { w.update(dt,1f,0f); assertSame(mesh,s.build(16f/9).cmd(0).mesh) }
        val sky=BufferedImage(960,540,BufferedImage.TYPE_INT_ARGB)
        val g=sky.createGraphics(); g.color=java.awt.Color(171,219,235); g.fillRect(0,0,960,540); g.dispose()
        val shot=SoftRaster(960,540).render(s.build(16f/9),sky)
        val dir=File("build/screens3d").apply { mkdirs() }
        ImageIO.write(shot,"png",File(dir,"open-world.png"))
    }
}
