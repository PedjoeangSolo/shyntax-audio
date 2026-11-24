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
private val eqPanel=Color(0xFF111D2B)
private val eqMuted=Color(0xFF8FA6BA)
private val eqFreq=listOf("20","25","31","40","50","63","80","100","125","160","200","250","315","400","500","630","800","1k","1.25k","1.6k","2k","2.5k","3.15k","4k","5k","6.3k","8k","10k","12.5k","16k","20k")

@Composable
fun EqualizerDialog(
    levels:List<Float>,enabled:Boolean,bandCount:Int,
    preamp:Float,bassBoost:Int,
    onDismiss:()->Unit,onEnabledChange:(Boolean)->Unit,
    onBandCountChange:(Int)->Unit,onBandChange:(Int,Float)->Unit,
    onPreampChange:(Float)->Unit,onBassBoostChange:(Int)->Unit,
    onPreset:(String)->Unit,onCustomPresetApply:(List<Float>)->Unit,
    onParametricApply:(List<Float>,List<Float>,List<Float>)->Unit
){
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("shyntax_eq_custom_presets",Context.MODE_PRIVATE)}
    var custom by remember{mutableStateOf(prefs.all.mapNotNull{e->
        val v=(e.value as? String)?.split(",")?.mapNotNull{it.toFloatOrNull()}
        if(v!=null&&v.size==31)e.key to v else null
    }.toMap())}
    var customName by remember{mutableStateOf("")}
    var menu by remember{mutableStateOf(false)}
    var tab by remember{mutableIntStateOf(0)}
    var selected by remember{mutableStateOf("Custom")}
    var freq by remember{mutableStateOf(listOf(60f,250f,1000f,4000f,12000f))}
    var gain by remember{mutableStateOf(List(5){0f})}
    var q by remember{mutableStateOf(List(5){1f})}
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=Color(0xFF080F19),
        title={Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text("SHYNTAX",color=eqBlue,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
            Text("Equalizer",color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("Precision sound control",color=eqMuted,fontSize=11.sp)
        }},
        text={
            Column(Modifier.fillMaxWidth().heightIn(max=680.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
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
                TabRow(selectedTabIndex=tab,containerColor=Color(0xFF080F19),contentColor=eqBlue){
                    Tab(selected=tab==0,onClick={tab=0},text={Text("Graphic EQ",fontSize=11.sp)})
                    Tab(selected=tab==1,onClick={tab=1},text={Text("Parametric",fontSize=11.sp)})
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
                }else{
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
