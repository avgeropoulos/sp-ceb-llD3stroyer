package com.roccoquest.team3d

import kotlin.math.*

/** Static scenery is uploaded once; only transforms change while playing. */
class OpenWorldScene(private val world: OpenWorld, private val combat: OpenWorldCombat? = null) {
    private val list = DrawList()
    private val model = Mat4()
    private val worldMesh = MeshBuilder().apply {
        val grass=0xFF76BC72.toInt(); val sand=0xFFEBC68C.toInt()
        fun ground(x0:Float,z0:Float,x1:Float,z1:Float,color:Int) {
            var x=x0
            while(x<x1) { var z=z0; while(z<z1) {
                box(x,-2f,z,min(x+5f,x1),0f,min(z+5f,z1),color); z+=5f
            }; x+=5f }
        }
        ground(-35f,-35f,14f,35f,grass)
        ground(14f,-35f,35f,-6f,grass)
        ground(14f,14f,35f,35f,grass)
        ground(30f,-6f,35f,14f,sand)
        box(14f,-2f,-6f,30f,-1.2f,14f,sand)
        for((i,p) in world.platforms.withIndex()) {
            val bottom=if(i<4 || p.x==9f || p.x==4.5f) 0f else p.top-0.7f
            box(p.x-p.w/2,bottom,p.z-p.d/2,p.x+p.w/2,p.top,p.z+p.d/2,p.color)
            box(p.x-p.w/2,p.top-0.12f,p.z-p.d/2,p.x+p.w/2,p.top,p.z+p.d/2,if(i<4) 0xFFA6D879.toInt() else p.color)
        }
        // Paths, flowers, trees and distant hills are decorative, outside the platform route.
        box(-1.8f,0.01f,-13f,1.8f,0.035f,17f,sand)
        box(-2f,0.01f,14f,5.4f,0.035f,16.5f,sand)
        for(i in 0..21) {
            val a=i*2.39996f; val radius=if(i%2==0) 30f else 25f
            val x=cos(a)*radius; val z=sin(a)*radius
            if(z< -12 || (x>13 && z<15) || (x< -14 && z<2) || (x>3 && z>13)) continue
            cylinder(x,0f,z,2.4f,0.28f,0xFF916347.toInt(),slices=6)
            ellipsoid(x,3.2f,z,1.8f,2.1f,1.8f,0xFF3B9461.toInt(),8,5)
            sphere(x+0.3f,3.8f,z+0.2f,1.3f,0xFF69BD72.toInt(),8,5)
        }
        for(i in 0..45) {
            val a=i*2.39996f; val r=18f+(i%5)*2f
            val x=cos(a)*r; val z=sin(a)*r
            if(x>13 || z< -12 || (x>2 && z>12)) continue
            cylinder(x,0f,z,0.3f,0.05f,0xFF418D59.toInt(),slices=4)
            sphere(x,0.35f,z,0.13f,if(i%2==0) 0xFFFFDD76.toInt() else 0xFFF6A8A7.toInt(),6,3)
        }
        // Home's roof is its platform. Door/windows indicate the home landmark.
        box(7.8f,0f,21.01f,9f,2.1f,21.05f,0xFF775149.toInt())
        box(10f,1.2f,21.01f,11.2f,2.3f,21.06f,0xFFB4E7F3.toInt())
        box(5.5f,3.2f,15f,12.5f,3.35f,21f,0xFFBD6F55.toInt())
        // Meadow checkpoint ring.
        cylinder(0f,0f,8f,0.05f,1.7f,0xFFF6DF8B.toInt(),slices=24)
    }.build()
    private val heroRocco = hero(false)
    private val heroBlocky = hero(true)
    private val foeMesh=MeshBuilder().apply {
        ellipsoid(0f,0.65f,0f,0.65f,0.65f,0.55f,0xFFAE684E.toInt(),10,6)
        ellipsoid(0f,1.05f,0f,0.8f,0.4f,0.65f,0xFFB95C69.toInt(),10,5)
        box(-0.35f,0.75f,0.5f,-0.1f,1.0f,0.62f,-1)
        box(0.1f,0.75f,0.5f,0.35f,1.0f,0.62f,-1)
        box(-0.23f,0.77f,0.6f,-0.1f,0.95f,0.65f,0xFF26344A.toInt())
        box(0.1f,0.77f,0.6f,0.23f,0.95f,0.65f,0xFF26344A.toInt())
    }.build()
    private val bossMesh=MeshBuilder().apply {
        ellipsoid(0f,0.9f,0f,1.1f,0.9f,0.85f,0xFF76A45E.toInt(),12,7)
        sphere(0f,1.5f,0.65f,0.7f,0xFFDFB570.toInt(),10,6)
        for(i in -1..1) cylinder(i*0.65f,1.5f,-0.3f,2.2f,0.22f,0xFFF1E8BD.toInt(),rTop=0f,slices=6)
        box(-0.45f,1.55f,1.15f,-0.12f,1.75f,1.28f,0xFF302C43.toInt())
        box(0.12f,1.55f,1.15f,0.45f,1.75f,1.28f,0xFF302C43.toInt())
    }.build()
    private val star = MeshBuilder().apply {
        val xs=FloatArray(10); val ys=FloatArray(10)
        for(i in 0..9) { val a=PI.toFloat()/2+i*PI.toFloat()/5; val r=if(i%2==0) 0.68f else 0.32f; xs[i]=cos(a)*r; ys[i]=sin(a)*r }
        prism(xs,ys,-0.14f,0.14f,0xFFFFDF68.toInt())
    }.build()
    private fun hero(blocky: Boolean) = MeshBuilder().apply {
        val shirt=if(blocky) 0xFF49BFC5.toInt() else 0xFFED6C54.toInt()
        box(-0.32f,0.38f,-0.22f,0.32f,1.05f,0.22f,shirt)
        box(-0.3f,0f,-0.2f,-0.03f,0.45f,0.22f,0xFF334E79.toInt())
        box(0.03f,0f,-0.2f,0.3f,0.45f,0.22f,0xFF334E79.toInt())
        if(blocky) box(-0.34f,1.03f,-0.3f,0.34f,1.7f,0.3f,0xFFF1BA8A.toInt())
        else sphere(0f,1.35f,0f,0.37f,0xFFF1BA8A.toInt(),10,7)
        box(-0.36f,1.56f,-0.31f,0.36f,1.78f,0.32f,if(blocky) 0xFF634B3C.toInt() else shirt)
        if(!blocky) box(-0.36f,1.55f,0f,0.36f,1.62f,0.5f,shirt)
        box(-0.2f,1.32f,0.29f,-0.1f,1.44f,0.36f,0xFF24344E.toInt())
        box(0.1f,1.32f,0.29f,0.2f,1.44f,0.36f,0xFF24344E.toInt())
        box(-0.49f,0.54f,-0.17f,-0.32f,1f,0.17f,0xFFF1BA8A.toInt())
        box(0.32f,0.54f,-0.17f,0.49f,1f,0.17f,0xFFF1BA8A.toInt())
    }.build()
    fun build(aspect: Float): DrawList {
        list.clear()
        list.env.fogNear=45f; list.env.fogFar=110f; list.env.fog=0xFFACDCEB.toInt()
        val tx=world.x; val ty=world.y+1.2f; val tz=world.z
        var dist=13f
        // Pull the camera toward the hero when terrain crosses the sight line.
        for(i in 1..20) {
            val d=13f*i/20; val cx=tx+sin(world.yaw)*d; val cz=tz+cos(world.yaw)*d; val cy=ty+d*0.65f
            if(world.platforms.any { abs(cx-it.x)<it.w/2+0.3f && abs(cz-it.z)<it.d/2+0.3f && cy<it.top+0.6f && cy> (if(it.top<=8 && it.x==0f) 0f else it.top-1f) }) { dist=max(2f,d-1); break }
        }
        val ex=tx+sin(world.yaw)*dist; val ez=tz+cos(world.yaw)*dist; val ey=ty+dist*0.65f
        list.camPos[0]=ex; list.camPos[1]=ey; list.camPos[2]=ez
        list.view.lookAt(ex,ey,ez,tx,ty,tz)
        list.proj.perspective(1.02f,aspect,0.1f,180f)
        list.viewProj.set(list.proj).mul(list.view)
        list.add(worldMesh,model.identity())
        // Surrounding sea and translucent lagoon.
        list.add(Prims.cube,model.identity().translate(0f,-2.4f,0f).scale(220f,0.2f,220f),0xFF62BAD0.toInt())
        list.add(Prims.cube,model.identity().translate(22f,-0.2f,4f).scale(16f,0.08f,20f),0x9954C9DF.toInt())
        for((i,c) in world.coins.withIndex()) if(world.coinBits and (1L shl i)==0L) {
            list.add(Prims.tube,model.identity().translate(c.x,c.y+sin(world.time*2+i)*0.12f,c.z).rotateY(world.time*2).rotateX(PI.toFloat()/2).scale(0.24f,0.1f,0.24f),0xFFFFCE58.toInt(),0.35f)
        }
        for((i,s) in world.stars.withIndex()) if(world.starBits and (1 shl i)==0 && (i!=5 || world.coinCount>=20)) {
            list.add(star,model.identity().translate(s.x,s.y+sin(world.time*2)*0.2f,s.z).rotateY(world.time),-1,0.45f)
        }
        val buddyY=min(world.y,world.floorAt(world.buddyX,world.buddyZ,world.y+0.5f))
        list.add(if(world.blocky) heroRocco else heroBlocky,model.identity().translate(world.buddyX,buddyY,world.buddyZ).rotateY(world.facing))
        list.add(if(world.blocky) heroBlocky else heroRocco,model.identity().translate(world.x,world.y,world.z).rotateY(world.facing))
        list.add(Prims.disc,model.identity().translate(world.x,world.floorAt(world.x,world.z,world.y+0.1f)+0.025f,world.z).scale(0.65f,1f,0.5f),0x44334754)
        combat?.let { battle ->
            for(e in battle.enemies) if(e.hp>0) {
                list.add(if(e.boss) bossMesh else foeMesh,model.identity().translate(e.x,e.y,e.z).rotateY(atan2(world.x-e.x,world.z-e.z)),if(e.hurt>0) 0xFFFFB5B5.toInt() else -1)
                val width=if(e.boss) 2.2f else 1.3f
                list.add(Prims.cube,model.identity().translate(e.x,e.y+2.6f,e.z).rotateY(world.yaw).scale(width,0.13f,0.1f),0xFF533D51.toInt(),1f)
                list.add(Prims.cube,model.identity().translate(e.x,e.y+2.6f,e.z).rotateY(world.yaw).scale(width*e.hp/e.maxHp,0.15f,0.12f),0xFF8DE09B.toInt(),1f)
            }
            for(s in battle.shots) list.add(if(s.kind==OpenWorldCombat.Attack.TNT) Prims.cube else Prims.smallBall,model.identity().translate(s.x,s.y,s.z).scale(if(s.kind==OpenWorldCombat.Attack.BOW) 0.16f else 0.3f),s.kind.color,0.55f)
            for(e in battle.effects) {
                val radius=e.radius*(1f-e.life/0.6f).coerceIn(0.2f,1f)
                list.add(Prims.ball,model.identity().translate(e.x,e.y,e.z).scale(radius,radius*0.35f,radius),(e.color and 0x00FFFFFF) or 0x66000000,0.7f)
            }
            if(battle.swing>0) list.add(Prims.cube,model.identity().translate(world.x,world.y+1f,world.z).rotateY(world.facing+battle.swing*10).translate(0.7f,0f,1f).scale(0.12f,0.18f,2.2f),0xFFE4F7FF.toInt(),0.7f)
        }
        list.finish()
        return list
    }
}
