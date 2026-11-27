package com.shyntax.audio

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val eqBlue=Color(0xFF62D9FF)
private val eqPanel=Color(0xFF0A0D11)
private val eqMuted=Color(0xFF93A8BC)
private val eqFreq=listOf("20","25","31","40","50","63","80","100","125","160","200","250","315","400","500","630","800","1k","1.25k","1.6k","2k","2.5k","3.15k","4k","5k","6.3k","8k","10k","12.5k","16k","20k")

@Composable
fun EqualizerDialog(
    levels:List<Float>,enabled:Boolean,bandCount:Int,
    preamp:Float,bassBoost:Int,limiterEnabled:Boolean,onLimiterChange:(Boolean)->Unit,
    onDismiss:()->Unit,onEnabledChange:(Boolean)->Unit,
    onBandCountChange:(Int)->Unit,onBandChange:(Int,Float)->Unit,
    onPreampChange:(Float)->Unit,onBassBoostChange:(Int)->Unit,
    onPreset:(String)->Unit,onCustomPresetApply:(List<Float>)->Unit,
    onParametricApply:(List<Float>,List<Float>,List<Float>)->Unit,
    initialTab:Int=0,
    speed:Float,pitchSemitones:Int,onSpeedChange:(Float)->Unit,onPitchChange:(Int)->Unit,onResetPlayback:()->Unit,
    dspBass:Int,dspTreble:Int,dspWidth:Int,dspLoudness:Int,reverb:ReverbSettings,
    onDspChange:(Int,Int,Int,Int)->Unit,onReverbChange:(ReverbSettings)->Unit
){
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("shyntax_eq_custom_presets",Context.MODE_PRIVATE)}
    var custom by remember{mutableStateOf(prefs.all.mapNotNull{e->
        val v=(e.value as? String)?.split(",")?.mapNotNull{it.toFloatOrNull()}
        if(v!=null&&v.size==31)e.key to v else null
    }.toMap())}
    var customName by remember{mutableStateOf("")}
    var menu by remember{mutableStateOf(false)}
    var tab by remember{mutableIntStateOf(initialTab)}
    var selected by remember{mutableStateOf("Custom")}
    var freq by remember{mutableStateOf(listOf(60f,250f,1000f,4000f,12000f))}
    var gain by remember{mutableStateOf(List(5){0f})}
    var q by remember{mutableStateOf(List(5){1f})}
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=Color(0xFF000000),
        title={Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text("SHYNTAX",color=eqBlue,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
            Text("Equalizer",color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("Precision sound control",color=eqMuted,fontSize=11.sp)
        }},
        text={
            Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
                Surface(color=eqPanel,shape=RoundedCornerShape(17.dp),modifier=Modifier.fillMaxWidth()){
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(if(enabled)"EQUALIZER ACTIVE" else "EQUALIZER BYPASSED",color=if(enabled)eqBlue else eqMuted,fontSize=10.sp,fontWeight=FontWeight.Bold)
                            Text(if(enabled)"Audio processing enabled" else "Sound remains unprocessed",color=Color.White,fontSize=12.sp)
                        }
                        Switch(checked=enabled,onCheckedChange=onEnabledChange)
                    }
                }
                Surface(color=eqPanel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(10.dp)){
                        Text("FREQUENCY RESPONSE",color=eqMuted,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)
                        Canvas(Modifier.fillMaxWidth().height(55.dp).padding(top=6.dp)){
                            for(i in 0..4){val y=size.height*i/4f;drawLine(Color.White.copy(alpha=.08f),Offset(0f,y),Offset(size.width,y),1.dp.toPx())}
                            val p=if(bandCount==31)levels.take(31) else (0 until 10).map{levels.getOrElse(it*30/9){0f}}
                            if(p.isNotEmpty()){
                                val path=Path()
                                p.forEachIndexed{i,v->val x=size.width*i/(p.size-1).coerceAtLeast(1);val y=size.height/2f-v.coerceIn(-12f,12f)/12f*(size.height/2f-4.dp.toPx());if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
                                drawPath(path,eqBlue,style=Stroke(2.5.dp.toPx(),cap=StrokeCap.Round))
                            }
                        }
                    }
                }
                TabRow(selectedTabIndex=tab,containerColor=Color(0xFF000000),contentColor=eqBlue){
                    Tab(selected=tab==0,onClick={tab=0},text={Text("Graphic EQ",fontSize=11.sp)})
                    Tab(selected=tab==1,onClick={tab=1},text={Text("Parametric",fontSize=11.sp)})
                    Tab(selected=tab==2,onClick={tab=2},text={Text("Tempo/Pitch",fontSize=11.sp)})
                    Tab(selected=tab==3,onClick={tab=3},text={Text("DSP",fontSize=11.sp)})
                }
                if(tab==0){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                        Text("BAND CONTROL",color=eqMuted,fontSize=9.sp,fontWeight=FontWeight.Bold)
                        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf(10,31).forEach{n->FilterChip(selected=bandCount==n,onClick={onBandCountChange(n)},label={Text(n.toString()+"-band",fontSize=10.sp)})}}
                    }
                    Surface(color=eqPanel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(horizontal=10.dp,vertical=6.dp)){
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("PREAMP",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(String.format("%+.1f dB",preamp),color=eqBlue,fontSize=11.sp)}
                            Slider(value=preamp,onValueChange=onPreampChange,valueRange=-12f..12f)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                                Column { Text("LIMITER",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold); Text(if(limiterEnabled)"Peak protection active" else "Peak protection disabled",color=eqMuted,fontSize=9.sp) }
                                Switch(checked=limiterEnabled,onCheckedChange=onLimiterChange)
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("BASS BOOST",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(bassBoost.toString()+"%",color=eqBlue,fontSize=11.sp)}
                            Slider(value=bassBoost.toFloat(),onValueChange={onBassBoostChange(it.roundToInt())},valueRange=0f..100f)
                        }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                        Text("PRESETS",color=eqMuted,fontSize=9.sp,fontWeight=FontWeight.Bold)
                        TextButton(onClick={selected="Flat";onPreset("Flat")},contentPadding=PaddingValues(0.dp)){Text("RESET FLAT",color=eqBlue,fontSize=9.sp)}
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        listOf("Flat","Bass","Treble","Vocal","Rock","Jazz","Pop","Classical").forEach{name->FilterChip(selected=selected==name,onClick={selected=name;onPreset(name)},label={Text(name,fontSize=10.sp)})}
                        if(custom.isNotEmpty())Box{
                            FilterChip(selected=false,onClick={menu=true},label={Text("My presets ▾",fontSize=10.sp)})
                            DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                                custom.keys.sorted().forEach{name->DropdownMenuItem(text={Text(name)},onClick={menu=false;selected=name;onCustomPresetApply(custom[name]?:List(31){0f})})}
                            }
                        }
                    }
                    Surface(color=eqPanel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(vertical=8.dp)){
                            Row(Modifier.padding(horizontal=10.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("GRAPHIC EQUALIZER",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(if(bandCount==31)"31 BANDS" else "10 BANDS",color=eqBlue,fontSize=10.sp)}
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                                val ix=if(bandCount==31)(0 until 31).toList()else(0 until 10).map{it*30/9}
                                ix.forEachIndexed{i,source->VerticalEqFader(levels.getOrElse(source){0f},if(bandCount==31)eqFreq[i] else listOf("31","62","125","250","500","1k","2k","4k","8k","16k")[i]){selected="Custom";onBandChange(source,it)}}
                            }
                            Text("Geser horizontal untuk melihat semua frekuensi",color=eqMuted,fontSize=9.sp,modifier=Modifier.align(Alignment.CenterHorizontally).padding(top=3.dp))
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        OutlinedTextField(value=customName,onValueChange={customName=it.take(22)},modifier=Modifier.weight(1f),singleLine=true,placeholder={Text("Nama preset",fontSize=11.sp)})
                        Button(enabled=customName.isNotBlank(),onClick={
                            val name=customName.trim();prefs.edit().putString(name,levels.take(31).joinToString(",")).apply()
                            custom=custom+(name to levels.take(31));selected=name;customName=""
                        },contentPadding=PaddingValues(horizontal=9.dp)){Text("Simpan",fontSize=10.sp)}
                    }
                }else if(tab==1){
                    Text("5-band parametric • frequency, gain and Q",color=eqMuted,fontSize=11.sp)
                    Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
                        freq.forEachIndexed{i,f->Surface(color=eqPanel,shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth()){
                            Column(Modifier.padding(9.dp)){
                                Text("Band "+(i+1)+" • "+f.roundToInt()+" Hz",color=Color.White,fontSize=11.sp,fontWeight=FontWeight.Bold)
                                Slider(value=f,onValueChange={v->freq=freq.toMutableList().also{it[i]=v}},valueRange=40f..16000f)
                                Row(verticalAlignment=Alignment.CenterVertically){Text("Gain",color=eqMuted,fontSize=10.sp);Slider(value=gain[i],onValueChange={v->gain=gain.toMutableList().also{it[i]=v}},valueRange=-12f..12f,modifier=Modifier.weight(1f));Text(gain[i].roundToInt().toString()+" dB",color=eqBlue,fontSize=10.sp)}
                                Row(verticalAlignment=Alignment.CenterVertically){Text("Q",color=eqMuted,fontSize=10.sp);Slider(value=q[i],onValueChange={v->q=q.toMutableList().also{it[i]=v}},valueRange=.3f..4f,modifier=Modifier.weight(1f));Text(String.format("%.1f",q[i]),color=eqBlue,fontSize=10.sp)}
                            }
                        }}
                    }
                    Button(onClick={onParametricApply(freq,gain,q);onEnabledChange(true)},modifier=Modifier.fillMaxWidth()){Text("APPLY PARAMETRIC EQ")}
                }else if(tab==2){
                    Surface(color=eqPanel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("TEMPO / KECEPATAN",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(String.format("%.2f×",speed),color=eqBlue,fontSize=11.sp)}
                            Slider(value=speed,onValueChange=onSpeedChange,valueRange=0.5f..2f)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("50%",color=eqMuted,fontSize=9.sp);Text("100%",color=eqMuted,fontSize=9.sp);Text("200%",color=eqMuted,fontSize=9.sp)}
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("PITCH",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold);Text((if(pitchSemitones>0)"+" else "")+pitchSemitones+" semitone",color=eqBlue,fontSize=11.sp)}
                            Slider(value=pitchSemitones.toFloat(),onValueChange={onPitchChange(it.toInt())},valueRange=-12f..12f,steps=23)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("-12",color=eqMuted,fontSize=9.sp);Text("0",color=eqMuted,fontSize=9.sp);Text("+12",color=eqMuted,fontSize=9.sp)}
                            TextButton(onClick=onResetPlayback,contentPadding=PaddingValues(0.dp)){Text("RESET TEMPO & PITCH",color=eqBlue,fontSize=9.sp)}
                            Text("Tempo dan pitch diproses oleh Media3. Kualitas time-stretch bergantung pada codec dan perangkat.",color=eqMuted,fontSize=9.sp)
                        }
                    }
                }else{
                    var dspB by remember(dspBass){mutableFloatStateOf(dspBass.toFloat())}
                    var dspT by remember(dspTreble){mutableFloatStateOf(dspTreble.toFloat())}
                    var dspW by remember(dspWidth){mutableFloatStateOf(dspWidth.toFloat())}
                    var dspL by remember(dspLoudness){mutableFloatStateOf(dspLoudness.toFloat())}
                    val dspSliderColors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=eqBlue,inactiveTrackColor=Color(0xFF3A4652))
                    Surface(color=eqPanel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("BASS BOOST",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(dspB.toInt().toString()+"%",color=eqBlue,fontSize=11.sp)}
                            Slider(value=dspB,onValueChange={dspB=it},valueRange=0f..100f,colors=dspSliderColors)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("TREBLE",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(dspT.toInt().toString()+" dB",color=eqBlue,fontSize=11.sp)}
                            Slider(value=dspT,onValueChange={dspT=it},valueRange=-12f..12f,colors=dspSliderColors)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("STEREO WIDTH / VIRTUALIZER",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(dspW.toInt().toString()+"%",color=eqBlue,fontSize=11.sp)}
                            Slider(value=dspW,onValueChange={dspW=it},valueRange=0f..100f,colors=dspSliderColors)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("LOUDNESS ENHANCER",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(dspL.toInt().toString()+" mB",color=eqBlue,fontSize=11.sp)}
                            Slider(value=dspL,onValueChange={dspL=it},valueRange=0f..1000f,steps=19,colors=dspSliderColors)
                            Text("Ketersediaan efek bergantung pada implementasi audio perangkat. Jika efek tidak didukung, aplikasi melewatinya dengan aman.",color=eqMuted,fontSize=9.sp)
                            Button(onClick={onDspChange(dspB.toInt(),dspT.toInt(),dspW.toInt(),dspL.toInt())},modifier=Modifier.fillMaxWidth()){Text("TERAPKAN DSP")}
                        }
                    }
                    ReverbPanel(reverb=reverb,onReverbChange=onReverbChange)
                }
            }
        },
        confirmButton={TextButton(onClick=onDismiss){Text("DONE",color=eqBlue)}}
    )
}

@Composable
private fun VerticalEqFader(value:Float,label:String,onValueChange:(Float)->Unit){
    val v=value.coerceIn(-12f,12f)
    Column(Modifier.width(38.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)){
        Text(v.roundToInt().toString(),color=eqBlue,fontSize=9.sp,fontWeight=FontWeight.Bold)
        Canvas(Modifier.width(34.dp).height(132.dp)
            .pointerInput(Unit){detectTapGestures{p->onValueChange((12f-p.y/size.height*24f).coerceIn(-12f,12f))}}
            .pointerInput(Unit){detectDragGestures{c,_->onValueChange((12f-c.position.y/size.height*24f).coerceIn(-12f,12f))}}
        ){
            val x=size.width/2f;val top=5.dp.toPx();val bottom=size.height-5.dp.toPx();val zero=(top+bottom)/2f
            val thumb=top+((12f-v)/24f)*(bottom-top)
            drawLine(Color(0xFF34495C),Offset(x,top),Offset(x,bottom),4.dp.toPx(),cap=StrokeCap.Round)
            drawLine(eqBlue.copy(alpha=.7f),Offset(x,zero),Offset(x,thumb),4.dp.toPx(),cap=StrokeCap.Round)
            drawLine(Color.White.copy(alpha=.35f),Offset(x-8.dp.toPx(),zero),Offset(x+8.dp.toPx(),zero),1.dp.toPx())
            drawCircle(Color.White,7.dp.toPx(),Offset(x,thumb));drawCircle(eqBlue,4.dp.toPx(),Offset(x,thumb))
        }
        Text(label,color=eqMuted,fontSize=8.sp,maxLines=1)
    }
}

@Composable
private fun ReverbKnob(label:String,value:Float,knobSize:androidx.compose.ui.unit.Dp,showCaption:Boolean=true,onValueChange:(Float)->Unit){
    val current by rememberUpdatedState(value)
    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)){
        Canvas(Modifier.size(knobSize).pointerInput(label){
            detectDragGestures{_,drag->
                onValueChange((current-drag.y/160f).coerceIn(0f,1f))
            }
        }){
            val cx=size.width/2f;val cy=size.height/2f;val r=size.minDimension/2f-1.5.dp.toPx()
            drawCircle(Color(0xFF1B1E24),radius=r,center=Offset(cx,cy))
            drawCircle(Color(0xFF3A4652),radius=r,center=Offset(cx,cy),style=Stroke(width=1.5.dp.toPx()))
            val theta=Math.toRadians((135f+current*270f).toDouble())
            val inner=r-10.dp.toPx();val outer=r-3.dp.toPx()
            drawLine(Color.White,
                Offset(cx+inner*kotlin.math.cos(theta).toFloat(),cy+inner*kotlin.math.sin(theta).toFloat()),
                Offset(cx+outer*kotlin.math.cos(theta).toFloat(),cy+outer*kotlin.math.sin(theta).toFloat()),
                strokeWidth=3.5.dp.toPx(),cap=StrokeCap.Round)
        }
        if(showCaption){
            Text(label,color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
            Text(String.format(java.util.Locale("id","ID"),"%.2f",value),color=eqMuted,fontSize=11.sp)
        }
    }
}

@Composable
private fun ReverbPanel(reverb:ReverbSettings,onReverbChange:(ReverbSettings)->Unit){
    var rv by remember(reverb){mutableStateOf(reverb)}
    fun update(next:ReverbSettings){rv=next;onReverbChange(next)}
    var presetMenu by remember{mutableStateOf(false)}
    val presets=remember{listOf(
        "Kamar Mandi" to ReverbSettings(enabled=true,damp=0.30f,filter=0.20f,decay=0.35f,preDelay=0.05f,preDelayMix=0.40f,size=0.25f,mix=0.50f),
        "Ruangan Kecil" to ReverbSettings(enabled=true,damp=0.45f,filter=0.30f,decay=0.45f,preDelay=0.08f,preDelayMix=0.35f,size=0.35f,mix=0.45f),
        "Aula" to ReverbSettings(enabled=true,damp=0.50f,filter=0.35f,decay=0.70f,preDelay=0.15f,preDelayMix=0.30f,size=0.70f,mix=0.50f),
        "Gereja" to ReverbSettings(enabled=true,damp=0.55f,filter=0.40f,decay=0.90f,preDelay=0.25f,preDelayMix=0.35f,size=0.95f,mix=0.55f),
        "Plate" to ReverbSettings(enabled=true,damp=0.70f,filter=0.50f,decay=0.60f,preDelay=0.02f,preDelayMix=0.20f,size=0.60f,mix=0.50f)
    )}
    Surface(color=eqPanel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                ReverbKnob("Damp",rv.damp,84.dp){update(rv.copy(damp=it))}
                ReverbKnob("Filter",rv.filter,84.dp){update(rv.copy(filter=it))}
                ReverbKnob("Pudar",rv.decay,84.dp){update(rv.copy(decay=it))}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                ReverbKnob("Pra-Delay",rv.preDelay,84.dp){update(rv.copy(preDelay=it))}
                ReverbKnob("Mix Pra-Delay",rv.preDelayMix,84.dp){update(rv.copy(preDelayMix=it))}
                ReverbKnob("Ukuran",rv.size,84.dp){update(rv.copy(size=it))}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                Button(onClick={update(rv.copy(enabled=!rv.enabled))},
                    colors=ButtonDefaults.buttonColors(containerColor=if(rv.enabled)eqBlue else Color(0xFF2A2F36),contentColor=if(rv.enabled)Color.Black else Color.White),
                    modifier=Modifier.weight(1f)){Text("Reverb",fontSize=11.sp)}
                Box(Modifier.weight(1f)){
                    OutlinedButton(onClick={presetMenu=true},modifier=Modifier.fillMaxWidth()){Text("Preset",fontSize=11.sp,color=Color.White)}
                    DropdownMenu(expanded=presetMenu,onDismissRequest={presetMenu=false}){
                        presets.forEach{(name,preset)->DropdownMenuItem(text={Text(name)},onClick={presetMenu=false;update(preset)})}
                    }
                }
                OutlinedButton(onClick={onReverbChange(rv)},modifier=Modifier.weight(1f)){Text("Simpan",fontSize=11.sp,color=Color.White)}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                Column{
                    Text("Mix",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Bold)
                    Text(String.format(java.util.Locale("id","ID"),"%.2f",rv.mix),color=eqMuted,fontSize=11.sp)
                }
                ReverbKnob("",rv.mix,132.dp,showCaption=false){update(rv.copy(mix=it))}
            }
            Text("Geser knob ke atas atau bawah untuk mengubah nilai. Reverb memakai efek ruangan bawaan Android; bila perangkat tidak mendukung parameter lengkap, aplikasi memakai preset ruangan terdekat.",color=eqMuted,fontSize=9.sp)
        }
    }
}
