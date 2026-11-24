package com.amz.pace

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.UUID
import kotlin.math.roundToInt

private const val PREF="pace"
private const val STORE="pace_workspace_v2"
private const val CHANNEL="amz_pace_alerts"
private val BG=Color(0xFF0D1728)
private val SURFACE=Color(0xFF18263B)
private val SURFACE2=Color(0xFF21334C)
private val ACCENT=Color(0xFF43D6B0)
private val BLUE=Color(0xFF79A9FF)
private val GOLD=Color(0xFFF2C66D)
private val DANGER=Color(0xFFFF858A)
private val FG=Color(0xFFF3F6FA)
private val MUTED=Color(0xFFA9B7CA)

data class Lesson(val id:String=UUID.randomUUID().toString(),val name:String,val topic:String,val start:String,val end:String,val started:Long=0,val finished:Long=0,val tests:Int=0) {
    val planned:Int get()=runCatching{Duration.between(LocalTime.parse(start),LocalTime.parse(end)).toMinutes().toInt()}.getOrDefault(0)
    val actual:Int get()=if(started>0&&finished>started)((finished-started)/60000).toInt() else 0
}
data class Plan(val id:String=UUID.randomUUID().toString(),val date:String=LocalDate.now().toString(),val target:Int=240,val lessons:List<Lesson>,val closed:Boolean=false) {
    val minutes:Int get()=lessons.sumOf{it.actual}
    val tests:Int get()=lessons.sumOf{it.tests}
}
data class StudyBreak(val start:String,val end:String,val minutes:Int)
internal fun studyBreaks(lessons:List<Lesson>):List<StudyBreak> {
    val sorted=lessons.sortedBy{runCatching{LocalTime.parse(it.start)}.getOrDefault(LocalTime.MIN)}
    return sorted.zipWithNext().mapNotNull{(a,b)->runCatching{val n=Duration.between(LocalTime.parse(a.end),LocalTime.parse(b.start)).toMinutes().toInt();if(n>0)StudyBreak(a.end,b.start,n)else null}.getOrNull()}
}
internal fun isValidPlan(target:Int,lessons:List<Lesson>):Boolean {
    if(target<=0||lessons.isEmpty())return false
    val s=lessons.sortedBy{it.start}
    val valid=s.all{runCatching{it.name.isNotBlank()&&it.topic.isNotBlank()&&LocalTime.parse(it.end).isAfter(LocalTime.parse(it.start))}.getOrDefault(false)}
    return valid&&s.zipWithNext().all{(a,b)->runCatching{!LocalTime.parse(a.end).isAfter(LocalTime.parse(b.start))}.getOrDefault(false)}
}
internal data class JalaliParts(val year:Int,val month:Int,val day:Int)
internal fun jalaliParts(date:LocalDate):JalaliParts {
    val gy=date.year-1600;val gm=date.monthValue-1;val gd=date.dayOfMonth-1
    val md=intArrayOf(0,31,if(date.isLeapYear)29 else 28,31,30,31,30,31,31,30,31,30,31)
    var days=365*gy+(gy+3)/4-(gy+99)/100+(gy+399)/400+gd
    for(i in 0 until gm)days+=md[i+1]
    var j=days-79;val cycle=j/12053;j%=12053
    var jy=979+33*cycle+4*(j/1461);j%=1461
    if(j>=366){jy+=(j-1)/365;j=(j-1)%365}
    val jm=if(j<186)1+j/31 else 7+(j-186)/30
    val jd=if(j<186)1+j%31 else 1+(j-186)%30
    return JalaliParts(jy,jm,jd)
}
internal fun jalaliToGregorian(year:Int,month:Int,day:Int):LocalDate {
    val jy=year-979;var days=365*jy+(jy/33)*8+((jy%33+3)/4)
    days+=if(month<=6)(month-1)*31 else 186+(month-7)*30
    days+=day-1+79
    var gy=1600+400*(days/146097);days%=146097;var leap=true
    if(days>=36525){days--;gy+=100*(days/36524);days%=36524;if(days>=365)days++ else leap=false}
    gy+=4*(days/1461);days%=1461
    if(days>=366){leap=false;days--;gy+=days/365;days%=365}
    val md=intArrayOf(31,if(leap)29 else 28,31,30,31,30,31,31,30,31,30,31);var m=0
    while(m<12&&days>=md[m]){days-=md[m];m++}
    return LocalDate.of(gy,m+1,days+1)
}
internal fun jalaliMonthLength(year:Int,month:Int):Int=if(month<=6)31 else if(month<=11)30 else if(jalaliParts(jalaliToGregorian(year,12,30)).day==30)30 else 29
private val spokenUnits=listOf("صفر","یک","دو","سه","چهار","پنج","شش","هفت","هشت","نه","ده","یازده","دوازده","سیزده","چهارده","پانزده","شانزده","هفده","هجده","نوزده")
private fun spokenNumber(n:Int):String=when{n<20->spokenUnits[n.coerceAtLeast(0)];n<60->{val tens=listOf("","","بیست","سی","چهل","پنجاه")[n/10];if(n%10==0)tens else tens+" و "+spokenUnits[n%10]};else->n.toString()}
internal fun speakTime(s:String):String=runCatching{val t=LocalTime.parse(s);"ساعت "+spokenNumber(t.hour)+" و "+spokenNumber(t.minute)+" دقیقه"}.getOrDefault(s)

data class StudyItem(val id:String=UUID.randomUUID().toString(),val subject:String,val title:String,val date:String,val start:String,val end:String,val note:String="",val done:Boolean=false,val studied:Int=0) {
    val duration:Int get()=runCatching{Duration.between(LocalTime.parse(start),LocalTime.parse(end)).toMinutes().toInt()}.getOrDefault(0)
}
data class TestResult(val id:String=UUID.randomUUID().toString(),val subject:String,val title:String,val date:String,val total:Int,val correct:Int,val wrong:Int,val blank:Int,val minutes:Int) {
    val percent:Int get()=if(total==0)0 else (correct*100f/total).roundToInt()
}
data class FocusRecord(val subject:String,val title:String,val date:String,val minutes:Int)
data class Workspace(val items:List<StudyItem> = emptyList(),val tests:List<TestResult> = emptyList(),val focus:List<FocusRecord> = emptyList(),val goal:Int=240,val activeId:String="",val activeSince:Long=0L,val elapsed:Long=0L)

private fun load(c:Context):Workspace=runCatching {
    val root=JSONObject(c.getSharedPreferences(PREF,0).getString(STORE,"{}")?:"{}")
    val ia=root.optJSONArray("items")?:JSONArray()
    val items=(0 until ia.length()).map{val o=ia.getJSONObject(it);StudyItem(o.optString("id"),o.optString("subject"),o.optString("title"),o.optString("date"),o.optString("start"),o.optString("end"),o.optString("note"),o.optBoolean("done"),o.optInt("studied"))}
    val ta=root.optJSONArray("tests")?:JSONArray()
    val tests=(0 until ta.length()).map{val o=ta.getJSONObject(it);TestResult(o.optString("id"),o.optString("subject"),o.optString("title"),o.optString("date"),o.optInt("total"),o.optInt("correct"),o.optInt("wrong"),o.optInt("blank"),o.optInt("minutes"))}
    val fa=root.optJSONArray("focus")?:JSONArray()
    val focus=(0 until fa.length()).map{val o=fa.getJSONObject(it);FocusRecord(o.optString("subject"),o.optString("title"),o.optString("date"),o.optInt("minutes"))}
    Workspace(items,tests,focus,root.optInt("goal",240),root.optString("activeId"),root.optLong("activeSince"),root.optLong("elapsed"))
}.getOrDefault(Workspace())
private fun save(c:Context,w:Workspace) {
    val items=JSONArray();w.items.forEach{items.put(JSONObject().put("id",it.id).put("subject",it.subject).put("title",it.title).put("date",it.date).put("start",it.start).put("end",it.end).put("note",it.note).put("done",it.done).put("studied",it.studied))}
    val tests=JSONArray();w.tests.forEach{tests.put(JSONObject().put("id",it.id).put("subject",it.subject).put("title",it.title).put("date",it.date).put("total",it.total).put("correct",it.correct).put("wrong",it.wrong).put("blank",it.blank).put("minutes",it.minutes))}
    val focus=JSONArray();w.focus.forEach{focus.put(JSONObject().put("subject",it.subject).put("title",it.title).put("date",it.date).put("minutes",it.minutes))}
    val root=JSONObject().put("items",items).put("tests",tests).put("focus",focus).put("goal",w.goal).put("activeId",w.activeId).put("activeSince",w.activeSince).put("elapsed",w.elapsed)
    c.getSharedPreferences(PREF,0).edit().putString(STORE,root.toString()).apply()
}

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);ensureChannel(this);setContent{PaceApp()}}
}
@Composable fun PaceAppContent(){
    val c= LocalContext.current
    var data by remember{mutableStateOf(load(c))}
    var page by remember{mutableIntStateOf(0)}
    var date by remember{mutableStateOf(LocalDate.now().toString())}
    var editor by remember{mutableStateOf<StudyItem?>(null)}
    var showEditor by remember{mutableStateOf(false)}
    var showTest by remember{mutableStateOf(false)}
    var tick by remember{mutableLongStateOf(System.currentTimeMillis())}
    var message by remember{mutableStateOf("")}
    var dark by remember{mutableStateOf(c.getSharedPreferences(PREF,0).getBoolean("dark_mode",true))}
    val snack= remember{SnackbarHostState()}
    fun commit(w:Workspace){data=w;save(c,w)}
    LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(1000);tick=System.currentTimeMillis();data=load(c)}}
    LaunchedEffect(message){if(message.isNotBlank()){snack.showSnackbar(message);message=""}}
    val active=data.items.firstOrNull{it.id==data.activeId}
    val elapsed=if(data.activeSince>0)data.elapsed+(tick-data.activeSince)else data.elapsed
    val bg=if(dark)BG else Color(0xFFF1F5F9)
    val card=if(dark)SURFACE else Color.White
    val fg=if(dark)FG else Color(0xFF17243A)
    val soft=if(dark)MUTED else Color(0xFF65758B)
    MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=ACCENT,background=bg,surface=card,onSurface=fg)else lightColorScheme(primary=Color(0xFF087F70),background=bg,surface=card,onSurface=fg)){
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl){
            Scaffold(containerColor=bg,snackbarHost={SnackbarHost(snack)},topBar={
                Row(Modifier.fillMaxWidth().background(bg).padding(horizontal=20.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text("AMZ PACE",color=ACCENT,fontSize=11.sp,fontWeight=FontWeight.Black,letterSpacing=2.sp);Text(listOf("داشبورد مطالعه","برنامه‌ریز درسی","تمرکز عمیق","تحلیل عملکرد","تقویم و سوابق","تنظیمات")[page],color=fg,fontSize=21.sp,fontWeight=FontWeight.Bold)}
                    IconButton(onClick={page=5}){Icon(Icons.Default.Settings,null,tint=soft)}
                    Box(Modifier.size(38.dp).clip(CircleShape).background(SURFACE2),contentAlignment=Alignment.Center){Text("A",color=ACCENT,fontWeight=FontWeight.Black)}
                }
            },bottomBar={
                NavigationBar(containerColor=card){
                    val labels=listOf("خانه","برنامه","تمرکز","تحلیل","تقویم")
                    val icons=listOf(Icons.Default.Home,Icons.Default.EventNote,Icons.Default.Timer,Icons.Default.Insights,Icons.Default.CalendarMonth)
                    labels.forEachIndexed{i,label->NavigationBarItem(selected=page==i,onClick={page=i},icon={Icon(icons[i],null)},label={Text(label,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=ACCENT,selectedTextColor=ACCENT,indicatorColor=ACCENT.copy(alpha=.12f),unselectedIconColor=soft,unselectedTextColor=soft))}
                }
            },floatingActionButton={
                if(page==1)FloatingActionButton(onClick={editor=null;showEditor=true},containerColor=ACCENT,contentColor=BG){Icon(Icons.Default.Add,null)}
                if(page==3)FloatingActionButton(onClick={showTest=true},containerColor=ACCENT,contentColor=BG){Icon(Icons.Default.AddTask,null)}
            }){padding->
                AnimatedContent(page,label="pages",modifier=Modifier.padding(padding)){p->
                    when(p){
                        0->Dashboard(data,active,elapsed,card,fg,soft,onPlan={page=1},onFocus={page=2},onAdd={page=1;editor=null;showEditor=true},onDone={item->commit(data.copy(items=data.items.map{if(it.id==item.id)it.copy(done=!it.done)else it}))})
                        1->Planner(data,date,card,fg,soft,onDate={date=it},onEdit={editor=it;showEditor=true},onDone={item->commit(data.copy(items=data.items.map{if(it.id==item.id)it.copy(done=!it.done)else it}))},onFocus={item->commit(data.copy(activeId=item.id,activeSince=System.currentTimeMillis(),elapsed=if(data.activeId==item.id)data.elapsed else 0L));page=2},onDelete={item->cancelStudyAlarm(c,item.id);commit(data.copy(items=data.items.filterNot{it.id==item.id}))},onAlarm={item->if(scheduleStudyAlarm(c,item))message="یادآوری جلسه تنظیم شد" else {runCatching{c.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};message="برای تنظیم زنگ، مجوز هشدار دقیق را فعال کن"}},onAdd={editor=null;showEditor=true})
                        2->Focus(active,elapsed,data,card,fg,soft,onStart={item->commit(data.copy(activeId=item.id,activeSince=System.currentTimeMillis(),elapsed=if(data.activeId==item.id)data.elapsed else 0L))},onPause={commit(data.copy(activeSince=0,elapsed=elapsed))},onStop={item->val mins=(elapsed/60000).toInt();commit(data.copy(items=data.items.map{if(it.id==item.id)it.copy(studied=it.studied+mins,done=true)else it},focus=data.focus+FocusRecord(item.subject,item.title,LocalDate.now().toString(),mins),activeId="",activeSince=0,elapsed=0));message="جلسه ثبت شد: "+mins+" دقیقه"},onChoose={page=1})
                        3->Analytics(data,card,fg,soft)
                        4->CalendarScreen(data,date,{date=it;page=1},card,fg,soft)
                        else->SettingsScreen(data,card,fg,soft,dark,{dark=it;c.getSharedPreferences(PREF,0).edit().putBoolean("dark_mode",it).apply()},{goal->commit(data.copy(goal=goal))},{message=it})
                    }
                }
            }
        }
    }
    if(showEditor)StudyEditor(editor,date,data.items,{showEditor=false},{item->commit(data.copy(items=if(data.items.any{it.id==item.id})data.items.map{if(it.id==item.id)item else it}else data.items+item));scheduleStudyAlarm(c,item);showEditor=false;message="جلسه ذخیره شد"})
    if(showTest)TestEditor(data.items.map{it.subject}.distinct(),{showTest=false},{test->commit(data.copy(tests=data.tests+test));showTest=false;message="نتیجه آزمون ثبت شد"})
}

@Composable private fun Dashboard(w:Workspace,active:StudyItem?,elapsed:Long,card:Color,fg:Color,soft:Color,onPlan:()->Unit,onFocus:()->Unit,onAdd:()->Unit,onDone:(StudyItem)->Unit){
    val today=LocalDate.now().toString()
    val todays=w.items.filter{it.date==today}.sortedBy{it.start}
    val minutes=w.focus.filter{it.date==today}.sumOf{it.minutes}
    val progress=(minutes.toFloat()/w.goal.coerceAtLeast(1)).coerceIn(0f,1f)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
        item{Card(colors=CardDefaults.cardColors(containerColor=SURFACE2),shape=RoundedCornerShape(25.dp)){Column(Modifier.fillMaxWidth().padding(19.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("امروز، "+jalali(LocalDate.now()),color=MUTED,fontSize=12.sp);Text("با تمرکز جلو برو",color=FG,fontSize=21.sp,fontWeight=FontWeight.Bold);Text("هر جلسه یک قدم به هدف نزدیک‌ترت می‌کند.",color=MUTED,fontSize=11.sp)};Box(Modifier.size(76.dp),contentAlignment=Alignment.Center){Canvas(Modifier.fillMaxSize()){drawArc(ACCENT.copy(alpha=.18f),-90f,360f,false,style=Stroke(8.dp.toPx()));drawArc(ACCENT,-90f,progress*360,false,style=Stroke(8.dp.toPx(),cap=StrokeCap.Round))};Text((progress*100).roundToInt().toString()+"٪",color=ACCENT,fontWeight=FontWeight.Bold)}}
            Spacer(Modifier.height(15.dp));Text(formatMinutes(minutes)+" از "+formatMinutes(w.goal),color=FG,fontSize=22.sp,fontWeight=FontWeight.Black);Spacer(Modifier.height(7.dp));LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),color=ACCENT,trackColor=Color.White.copy(alpha=.12f))
        }}}
        item{Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){MiniMetric("جلسه‌ها",todays.size.toString(),Icons.Default.MenuBook,ACCENT,card,fg,Modifier.weight(1f));MiniMetric("تکمیل‌شده",todays.count{it.done}.toString(),Icons.Default.CheckCircle,BLUE,card,fg,Modifier.weight(1f));MiniMetric("آزمون‌ها",w.tests.count{it.date==today}.toString(),Icons.Default.Quiz,GOLD,card,fg,Modifier.weight(1f))}}
        item{Card(colors=CardDefaults.cardColors(containerColor=card),shape=RoundedCornerShape(19.dp)){Column(Modifier.padding(16.dp)){
            Text(if(active==null)"آماده شروع یک جلسه‌ای؟" else "جلسه فعال",color=ACCENT,fontWeight=FontWeight.Bold,fontSize=12.sp)
            Text(if(active==null)"برنامه امروزت را مدیریت کن" else active.subject+" · "+active.title,color=fg,fontSize=17.sp,fontWeight=FontWeight.Bold)
            if(active!=null)Text("زمان سپری‌شده "+clock(elapsed),color=soft)
            Spacer(Modifier.height(10.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=if(active==null)onPlan else onFocus,modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=ACCENT,contentColor=BG)){Text(if(active==null)"برنامه من" else "ادامه تمرکز")};OutlinedButton(onClick=onAdd,modifier=Modifier.weight(1f)){Icon(Icons.Default.Add,null);Text("جلسه جدید")}}
        }}}
        item{Row(verticalAlignment=Alignment.CenterVertically){Text("برنامه امروز",color=fg,fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text(todays.count{it.done}.toString()+" / "+todays.size,color=soft,fontSize=12.sp)}}
        if(todays.isEmpty())item{Empty("روزت را برنامه‌ریزی کن","اولین جلسه مطالعه را اضافه کن.",Icons.Default.EventNote)}
        items(todays,key={it.id}){item->ItemCard(item,card,fg,soft,{onDone(item)},{},{},{},{})}
        item{TextButton(onClick=onPlan,modifier=Modifier.fillMaxWidth()){Text("مشاهده برنامه کامل");Icon(Icons.Default.ArrowBack,null)}}
    }
}

@Composable private fun Planner(w:Workspace,date:String,card:Color,fg:Color,soft:Color,onDate:(String)->Unit,onEdit:(StudyItem)->Unit,onDone:(StudyItem)->Unit,onFocus:(StudyItem)->Unit,onDelete:(StudyItem)->Unit,onAlarm:(StudyItem)->Unit,onAdd:()->Unit){
    val d=runCatching{LocalDate.parse(date)}.getOrDefault(LocalDate.now())
    val list=w.items.filter{it.date==date}.sortedBy{it.start}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Card(colors=CardDefaults.cardColors(containerColor=SURFACE2),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(16.dp)){
            Text("برنامه‌ریزی هوشمند",color=FG,fontSize=19.sp,fontWeight=FontWeight.Bold);Text("جلسه‌ها را مرتب کن و فاصله‌های استراحت را ببین.",color=MUTED,fontSize=11.sp)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton(onClick={onDate(d.minusDays(1).toString())}){Icon(Icons.Default.ChevronRight,null,tint=FG)};Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Text(jalali(d),color=ACCENT,fontWeight=FontWeight.Bold,fontSize=16.sp);Text(if(d==LocalDate.now())"امروز" else d.dayOfWeek.name,color=MUTED,fontSize=10.sp)};IconButton(onClick={onDate(d.plusDays(1).toString())}){Icon(Icons.Default.ChevronLeft,null,tint=FG)}}
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){(0..6).forEach{n->val day=LocalDate.now().plusDays(n.toLong());val selected=day.toString()==date;Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if(selected)ACCENT else Color.White.copy(alpha=.06f)).clickable{onDate(day.toString())}.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(listOf("ش","ی","د","س","چ","پ","ج")[day.dayOfWeek.value%7],color=if(selected)BG else MUTED,fontSize=10.sp);Text(jalaliParts(day).day.toString(),color=if(selected)BG else FG,fontWeight=FontWeight.Bold)}}}
        }}}
        item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("جلسه‌های روز",color=fg,fontSize=18.sp,fontWeight=FontWeight.Bold);Text(list.size.toString()+" جلسه · "+list.sumOf{it.duration}+" دقیقه",color=soft,fontSize=11.sp)};FilledTonalButton(onClick=onAdd){Icon(Icons.Default.Add,null);Text("افزودن")}}}
        if(list.isEmpty())item{Empty("برنامه‌ای برای این روز نیست","یک جلسه جدید تعریف کن.",Icons.Default.EventNote)}
        items(list,key={it.id}){item->ItemCard(item,card,fg,soft,{onDone(item)},{onAlarm(item)},{onEdit(item);onDelete(item)})}
        val gaps=studyBreaks(list.map{Lesson(name=it.subject,topic=it.title,start=it.start,end=it.end)})
        if(gaps.isNotEmpty()){item{Text("استراحت‌های پیشنهادی",color=fg,fontSize=16.sp,fontWeight=FontWeight.Bold)};items(gaps){g->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(card).padding(13.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Coffee,null,tint=GOLD);Spacer(Modifier.width(9.dp));Text(g.start+" – "+g.end,color=fg,modifier=Modifier.weight(1f));Text(g.minutes.toString()+" دقیقه",color=GOLD,fontWeight=FontWeight.Bold)}}}
    }
}

@Composable private fun Focus(active:StudyItem?,elapsed:Long,w:Workspace,card:Color,fg:Color,soft:Color,onStart:(StudyItem)->Unit,onPause:()->Unit,onStop:(StudyItem)->Unit,onChoose:()->Unit){
    Column(Modifier.fillMaxSize().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(15.dp)){
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(245.dp),contentAlignment=Alignment.Center){Canvas(Modifier.fillMaxSize()){drawArc(ACCENT.copy(alpha=.15f),-90f,360f,false,style=Stroke(12.dp.toPx()));if(active!=null)drawArc(ACCENT,-90f,((elapsed/1000)%3600)/3600f*360f,false,style=Stroke(12.dp.toPx(),cap=StrokeCap.Round))};Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.SelfImprovement,null,tint=ACCENT,modifier=Modifier.size(30.dp));Text(clock(elapsed),color=fg,fontSize=42.sp,fontWeight=FontWeight.Black);Text(if(active==null)"آماده شروع" else "در حال تمرکز",color=soft,fontSize=12.sp)}}
        if(active==null){Text("زمان تمرکز",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("جلسه‌ای را از برنامه انتخاب کن تا زمان واقعی مطالعه ثبت شود.",color=soft,textAlign=TextAlign.Center);Button(onClick=onChoose,colors=ButtonDefaults.buttonColors(containerColor=ACCENT,contentColor=BG)){Icon(Icons.Default.List,null);Text("انتخاب جلسه")}}
        else{Text(active.subject,color=ACCENT,fontWeight=FontWeight.Bold);Text(active.title,color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold);Text(active.start+" تا "+active.end+" · "+active.duration+" دقیقه",color=soft);Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){if(w.activeSince>0)OutlinedButton(onClick=onPause){Icon(Icons.Default.Pause,null);Text("مکث")}else Button(onClick={onStart(active)},colors=ButtonDefaults.buttonColors(containerColor=ACCENT,contentColor=BG)){Icon(Icons.Default.PlayArrow,null);Text("ادامه")};Button(onClick={onStop(active)},colors=ButtonDefaults.buttonColors(containerColor=BLUE,contentColor=BG)){Icon(Icons.Default.Stop,null);Text("پایان جلسه")}}}
        Card(colors=CardDefaults.cardColors(containerColor=card),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("حالت مطالعه عمیق",color=fg,fontWeight=FontWeight.Bold);Text("یک مبحث مشخص را انتخاب کن، اعلان‌های مزاحم را کنار بگذار و در پایان زمان واقعی را ثبت کن.",color=soft,fontSize=12.sp)}}
    }
}

@Composable
private fun Analytics(w: Workspace, card: Color, fg: Color, soft: Color) {
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { d -> w.focus.filter { it.date == d.toString() }.sumOf { it.minutes } }
    val recentTests = w.tests.filter { it.date >= today.minusDays(6).toString() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Column {
                Text("تحلیل هفتگی", color = fg, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("بر اساس داده‌های ثبت‌شده در برنامه", color = soft, fontSize = 11.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniMetric("مطالعه", formatMinutes(values.sum()), Icons.Default.Timer, ACCENT, card, fg, Modifier.weight(1f))
                MiniMetric("تست", recentTests.sumOf { it.total }.toString(), Icons.Default.Quiz, GOLD, card, fg, Modifier.weight(1f))
                val average = if (recentTests.isEmpty()) 0 else recentTests.map { it.percent }.average().roundToInt()
                MiniMetric("میانگین درصد", average.toString() + "٪", Icons.Default.TrendingUp, BLUE, card, fg, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("نمودار زمان مطالعه", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    val maxValue = (values.maxOrNull() ?: 60).coerceAtLeast(60)
                    Row(
                        Modifier.fillMaxWidth().height(150.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        values.forEachIndexed { index, value ->
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                Text(if (value > 0) value.toString() else "", color = soft, fontSize = 9.sp)
                                Box(
                                    Modifier.width(19.dp)
                                        .height((value.toFloat() / maxValue * 105).coerceAtLeast(4f).dp)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                        .background(if (index == 6) ACCENT else BLUE.copy(alpha = .65f))
                                )
                                Text(
                                    listOf("ش", "ی", "د", "س", "چ", "پ", "ج")[days[index].dayOfWeek.value % 7],
                                    color = soft, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("تحلیل آزمون‌ها", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (recentTests.isEmpty()) Text("هنوز آزمونی ثبت نشده است.", color = soft)
                    recentTests.sortedByDescending { it.date }.forEach { result ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(result.subject + " · " + result.title, color = fg, fontWeight = FontWeight.SemiBold)
                                Text(result.date + " · " + result.correct + " صحیح / " + result.wrong + " غلط / " + result.blank + " نزده", color = soft, fontSize = 10.sp)
                            }
                            Text(result.percent.toString() + "٪", color = ACCENT, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        HorizontalDivider(color = soft.copy(alpha = .15f))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("مقایسه درس‌ها", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    val subjects = (w.items.map { it.subject } + w.tests.map { it.subject }).distinct()
                    if (subjects.isEmpty()) Text("با ثبت مطالعه و آزمون، تحلیل درس‌ها نمایش داده می‌شود.", color = soft)
                    subjects.forEach { subject ->
                        val minutes = w.focus.filter { it.subject == subject }.sumOf { it.minutes }
                        val subjectTests = w.tests.filter { it.subject == subject }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(subject, color = fg, fontWeight = FontWeight.SemiBold)
                                Text(formatMinutes(minutes) + " مطالعه · " + subjectTests.size + " آزمون", color = soft, fontSize = 10.sp)
                            }
                            val score = if (subjectTests.isEmpty()) "—" else subjectTests.map { it.percent }.average().roundToInt().toString() + "٪"
                            Text(score, color = ACCENT, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarScreen(w: Workspace, selected: String, onSelect: (String) -> Unit, card: Color, fg: Color, soft: Color) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val first = month.atDay(1)
    val blanks = first.dayOfWeek.value % 7
    val dates: List<LocalDate?> = (0 until blanks).map { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val chosen = runCatching { LocalDate.parse(selected) }.getOrDefault(LocalDate.now())
    Column(Modifier.fillMaxSize().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(21.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronRight, null, tint = fg) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val jp = jalaliParts(month.atDay(1))
                        Text(jalaliMonthName(jp.month) + " " + jp.year, color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("تقویم فعالیت‌ها", color = soft, fontSize = 10.sp)
                    }
                    IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronLeft, null, tint = fg) }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("ش", "ی", "د", "س", "چ", "پ", "ج").forEach {
                        Text(it, Modifier.weight(1f), color = soft, textAlign = TextAlign.Center, fontSize = 11.sp)
                    }
                }
                dates.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).padding(3.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (day == chosen) ACCENT else if (day == LocalDate.now()) BLUE.copy(alpha = .2f) else Color.Transparent)
                                    .clickable(enabled = day != null) { day?.let { onSelect(it.toString()) } },
                                contentAlignment = Alignment.Center
                            ) {
                                if (day != null) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(jalaliParts(day).day.toString(), color = if (day == chosen) BG else fg, fontWeight = FontWeight.Bold)
                                        if (w.items.any { it.date == day.toString() }) {
                                            Box(Modifier.size(4.dp).clip(CircleShape).background(if (day == chosen) BG else ACCENT))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("سوابق " + jalali(chosen), color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        val list = w.items.filter { it.date == selected }.sortedBy { it.start }
        if (list.isEmpty()) {
            Empty("داده‌ای برای این روز نیست", "روز دیگری را انتخاب کن یا برنامه‌ای ثبت کن.", Icons.Default.EventBusy)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(card).padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.subject + " · " + item.title, color = fg, fontWeight = FontWeight.SemiBold)
                            Text(item.start + " – " + item.end, color = soft, fontSize = 11.sp)
                        }
                        Text(if (item.done) "انجام شد" else "برنامه", color = if (item.done) ACCENT else GOLD, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(w:Workspace,card:Color,fg:Color,soft:Color,dark:Boolean,onTheme:(Boolean)->Unit,onGoal:(Int)->Unit,onMessage:(String)->Unit){
    val context=LocalContext.current
    var goal by remember{mutableStateOf(w.goal.toString())}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Card(colors=CardDefaults.cardColors(containerColor=card),shape=RoundedCornerShape(19.dp)){Column(Modifier.padding(16.dp)){Text("ظاهر برنامه",color=fg,fontSize=17.sp,fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("حالت تاریک",color=fg,modifier=Modifier.weight(1f));Switch(dark,onTheme)}}}}
        item{Card(colors=CardDefaults.cardColors(containerColor=card),shape=RoundedCornerShape(19.dp)){Column(Modifier.padding(16.dp)){Text("هدف روزانه مطالعه",color=fg,fontSize=17.sp,fontWeight=FontWeight.Bold);Text("هدف را بر اساس دقیقه تنظیم کن.",color=soft,fontSize=11.sp);OutlinedTextField(goal,{goal=it},label={Text("دقیقه در روز")},singleLine=true);Button(onClick={val n=goal.toIntOrNull();if(n!=null&&n in 15..1440){onGoal(n);onMessage("هدف روزانه ذخیره شد")}else onMessage("عدد باید بین ۱۵ تا ۱۴۴۰ باشد")},colors=ButtonDefaults.buttonColors(containerColor=ACCENT,contentColor=BG)){Text("ذخیره هدف")}}}}
        item{Card(colors=CardDefaults.cardColors(containerColor=card),shape=RoundedCornerShape(19.dp)){Column(Modifier.padding(16.dp)){Text("یادآوری‌ها",color=fg,fontSize=17.sp,fontWeight=FontWeight.Bold);Text("برای هشدار دقیق، مجوز اعلان و هشدار دقیق اندروید باید فعال باشد.",color=soft,fontSize=12.sp);Button(onClick={runCatching{context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}},colors=ButtonDefaults.buttonColors(containerColor=BLUE,contentColor=BG)){Icon(Icons.Default.NotificationsActive,null);Text("باز کردن تنظیمات مجوزها")}}}}
        item{Card(colors=CardDefaults.cardColors(containerColor=card),shape=RoundedCornerShape(19.dp)){Column(Modifier.padding(16.dp)){Text("درباره AMZ Pace",color=fg,fontSize=17.sp,fontWeight=FontWeight.Bold);Text("نسخه بازطراحی‌شده مدیریت مطالعه",color=soft,fontSize=12.sp);Text("برنامه‌ریزی · تمرکز · تحلیل · آزمون",color=ACCENT,fontSize=13.sp);Spacer(Modifier.height(8.dp));Text("ساخته شده توسط امین",color=soft,fontSize=11.sp)}}}
    }
}

@Composable private fun StudyEditor(old:StudyItem?,date:String,existing:List<StudyItem>,close:()->Unit,saveItem:(StudyItem)->Unit){
    var subject by remember{mutableStateOf(old?.subject?:"")};var title by remember{mutableStateOf(old?.title?:"")};var day by remember{mutableStateOf(old?.date?:date)};var start by remember{mutableStateOf(old?.start?:"09:00")};var end by remember{mutableStateOf(old?.end?:"10:00")};var note by remember{mutableStateOf(old?.note?:"")};var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=close,containerColor=SURFACE,title={Text(if(old==null)"ساخت جلسه مطالعه" else "ویرایش جلسه",color=FG,fontWeight=FontWeight.Bold)},text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
        OutlinedTextField(subject,{subject=it},label={Text("نام درس")},singleLine=true);OutlinedTextField(title,{title=it},label={Text("مبحث / عنوان")},singleLine=true);OutlinedTextField(day,{day=it},label={Text("تاریخ YYYY-MM-DD")},singleLine=true)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(start,{start=it},modifier=Modifier.weight(1f),label={Text("شروع HH:MM")},singleLine=true);OutlinedTextField(end,{end=it},modifier=Modifier.weight(1f),label={Text("پایان HH:MM")},singleLine=true)}
        OutlinedTextField(note,{note=it},label={Text("یادداشت")},minLines=2);if(error.isNotBlank())Text(error,color=DANGER,fontSize=11.sp)
    }},confirmButton={Button(onClick={
        val valid=runCatching{LocalDate.parse(day);LocalTime.parse(start);LocalTime.parse(end).isAfter(LocalTime.parse(start))}.getOrDefault(false)
        val overlap=existing.any{it.id!=old?.id&&it.date==day&&start<it.end&&end>it.start}
        when{subject.isBlank()||title.isBlank()->error="نام درس و مبحث الزامی است";!valid->error="تاریخ یا ساعت معتبر نیست";overlap->error="این ساعت با جلسه دیگری تداخل دارد";else->saveItem(StudyItem(old?.id?:UUID.randomUUID().toString(),subject.trim(),title.trim(),day,start,end,note.trim(),old?.done?:false,old?.studied?:0))}
    },colors=ButtonDefaults.buttonColors(containerColor=ACCENT,contentColor=BG)){Text("ذخیره")}},dismissButton={TextButton(onClick=close){Text("انصراف",color=MUTED)}})
}
@Composable private fun TestEditor(subjects:List<String>,close:()->Unit,saveTest:(TestResult)->Unit){
    var subject by remember{mutableStateOf(subjects.firstOrNull()?:"")};var title by remember{mutableStateOf("")};var total by remember{mutableStateOf("20")};var correct by remember{mutableStateOf("15")};var wrong by remember{mutableStateOf("3")};var blank by remember{mutableStateOf("2")};var mins by remember{mutableStateOf("30")};var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=close,containerColor=SURFACE,title={Text("ثبت نتیجه آزمون",color=FG,fontWeight=FontWeight.Bold)},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        OutlinedTextField(subject,{subject=it},label={Text("درس")});OutlinedTextField(title,{title=it},label={Text("عنوان آزمون")})
        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){OutlinedTextField(total,{total=it},modifier=Modifier.weight(1f),label={Text("کل")});OutlinedTextField(correct,{correct=it},modifier=Modifier.weight(1f),label={Text("صحیح")})}
        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){OutlinedTextField(wrong,{wrong=it},modifier=Modifier.weight(1f),label={Text("غلط")});OutlinedTextField(blank,{blank=it},modifier=Modifier.weight(1f),label={Text("نزده")})}
        OutlinedTextField(mins,{mins=it},label={Text("زمان دقیقه")});if(error.isNotBlank())Text(error,color=DANGER)
    }},confirmButton={Button(onClick={val t=total.toIntOrNull()?:0;val c=correct.toIntOrNull()?:-1;val x=wrong.toIntOrNull()?:-1;val b=blank.toIntOrNull()?:-1;val m=mins.toIntOrNull()?:-1;if(subject.isBlank()||t<=0||c<0||x<0||b<0||c+x+b!=t||m<0)error="مقادیر آزمون را بررسی کن" else saveTest(TestResult(subject=subject,title=title,date=LocalDate.now().toString(),total=t,correct=c,wrong=x,blank=b,minutes=m))},colors=ButtonDefaults.buttonColors(containerColor=ACCENT,contentColor=BG)){Text("ثبت")}},dismissButton={TextButton(onClick=close){Text("انصراف",color=MUTED)}})
}
@Composable private fun ItemCard(item:StudyItem,card:Color,fg:Color,soft:Color,onDone:()->Unit,onFocus:()->Unit,onAlarm:()->Unit,onEdit:()->Unit,onDelete:()->Unit){
    Card(colors=CardDefaults.cardColors(containerColor=card),shape=RoundedCornerShape(17.dp),modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.width(53.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(item.start,color=ACCENT,fontWeight=FontWeight.Bold,fontSize=13.sp);Box(Modifier.padding(vertical=4.dp).width(2.dp).height(20.dp).background(ACCENT.copy(alpha=.4f)));Text(item.end,color=soft,fontSize=11.sp)}
            Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(item.subject,color=fg,fontWeight=FontWeight.Bold);Text(item.title,color=soft,fontSize=11.sp);Text(item.duration.toString()+" دقیقه · "+if(item.done)"انجام شد" else "برنامه‌ریزی‌شده",color=if(item.done)ACCENT else GOLD,fontSize=10.sp)}
            Column{IconButton(onClick=onDone){Icon(if(item.done)Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,null,tint=if(item.done)ACCENT else soft)};Row{IconButton(onClick=onFocus){Icon(Icons.Default.PlayArrow,null,tint=ACCENT,modifier=Modifier.size(18.dp))};IconButton(onClick=onAlarm){Icon(Icons.Default.NotificationsActive,null,tint=GOLD,modifier=Modifier.size(18.dp))};IconButton(onClick=onEdit){Icon(Icons.Default.Edit,null,tint=soft,modifier=Modifier.size(18.dp))};IconButton(onClick=onDelete){Icon(Icons.Default.Delete,null,tint=DANGER,modifier=Modifier.size(18.dp))}}}
        }
    }
}
@Composable private fun MiniMetric(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector,color:Color,card:Color,fg:Color,modifier:Modifier=Modifier){
    Card(modifier=modifier,colors=CardDefaults.cardColors(containerColor=card),shape=RoundedCornerShape(15.dp)){Column(Modifier.fillMaxWidth().padding(10.dp)){Icon(icon,null,tint=color,modifier=Modifier.size(18.dp));Spacer(Modifier.height(7.dp));Text(value,color=fg,fontWeight=FontWeight.Black,fontSize=17.sp);Text(label,color=MUTED,fontSize=9.sp,maxLines=1)}}
}
@Composable private fun Empty(title:String,description:String,icon:androidx.compose.ui.graphics.vector.ImageVector){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SURFACE).padding(23.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null,tint=ACCENT,modifier=Modifier.size(30.dp));Spacer(Modifier.height(8.dp));Text(title,color=FG,fontWeight=FontWeight.Bold);Text(description,color=MUTED,fontSize=11.sp,textAlign=TextAlign.Center)}}
private fun formatMinutes(n:Int)=if(n<60)n.toString()+" دقیقه" else (n/60).toString()+" ساعت "+(n%60).toString()+" دقیقه"
private fun clock(ms:Long):String{val s=(ms/1000).coerceAtLeast(0);return "%02d:%02d:%02d".format(s/3600,(s%3600)/60,s%60)}
private fun jalali(d:LocalDate):String{val p=jalaliParts(d);return p.day.toString()+" "+jalaliMonthName(p.month)+" "+p.year}
private fun jalaliMonthName(m:Int)=listOf("فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور","مهر","آبان","آذر","دی","بهمن","اسفند").getOrElse(m-1){"ماه"}
private fun ensureChannel(c:Context){if(Build.VERSION.SDK_INT>=26){val ch=NotificationChannel(CHANNEL,"هشدارهای مطالعه",NotificationManager.IMPORTANCE_HIGH).apply{description="یادآوری صوتی و دیداری جلسه‌های مطالعه";enableVibration(true)};c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)}}
private fun scheduleStudyAlarm(c:Context,item:StudyItem):Boolean{
    val at=runCatching{LocalDate.parse(item.date).atTime(LocalTime.parse(item.start)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()}.getOrDefault(0L);if(at<=System.currentTimeMillis())return false
    val intent=Intent(c,StudyAlarmReceiver::class.java).putExtra("subject",item.subject).putExtra("title",item.title).putExtra("start",item.start)
    val pi=PendingIntent.getBroadcast(c,item.id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val am=c.getSystemService(AlarmManager::class.java);return runCatching{if(Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi)else am.setExact(AlarmManager.RTC_WAKEUP,at,pi)}.isSuccess
}
private fun cancelStudyAlarm(c:Context,id:String){val pi=PendingIntent.getBroadcast(c,id.hashCode(),Intent(c,StudyAlarmReceiver::class.java),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE);if(pi!=null){c.getSystemService(AlarmManager::class.java).cancel(pi);pi.cancel()}}
class StudyAlarmReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        ensureChannel(context)
        val subject=intent.getStringExtra("subject")?:"جلسه مطالعه";val title=intent.getStringExtra("title")?:"";val time=intent.getStringExtra("start")?:""
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_amz_pace).setContentTitle("زمان شروع مطالعه").setContentText(subject+" · "+title+" · "+time).setStyle(NotificationCompat.BigTextStyle().bigText("زمان شروع جلسه "+subject+"، مبحث "+title+" فرا رسیده است. برنامه را باز کن و تایمر تمرکز را شروع کن.")).setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true).setDefaults(NotificationCompat.DEFAULT_ALL).setContentIntent(open).build()
        val nm=context.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)nm.notify((subject+title+time).hashCode(),n)
    }
}
class PaceBootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==Intent.ACTION_BOOT_COMPLETED)load(context).items.filterNot{it.done}.forEach{scheduleStudyAlarm(context,it)}}}
