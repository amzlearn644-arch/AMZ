
package com.amz.pace

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.Locale
import java.util.UUID

private val ink=Color(0xFF101C32); private val teal=Color(0xFF2DD4BF)
private val paper=Color(0xFFF4F1E8); private val muted=Color(0xFF9AA9BA)
private const val PREF="pace"; private const val CH="pace-alert"
data class Lesson(val id:String=UUID.randomUUID().toString(),val name:String,val topic:String,val start:String,val end:String,val started:Long=0,val finished:Long=0,val tests:Int=0){
 val planned:Int get()=runCatching{Duration.between(LocalTime.parse(start),LocalTime.parse(end)).toMinutes().toInt()}.getOrDefault(0)
 val actual:Int get()=if(started>0&&finished>started)((finished-started)/60000).toInt() else 0
}
data class Plan(val date:String=LocalDate.now().toString(),val target:Int=240,val lessons:List<Lesson>,val closed:Boolean=false){
 val minutes:Int get()=lessons.sumOf{it.actual}; val tests:Int get()=lessons.sumOf{it.tests}
}
private fun load(c:Context):List<Plan> = runCatching{
 val a=JSONArray(c.getSharedPreferences(PREF,0).getString("plans","[]"))
 (0 until a.length()).map{ i-> val o=a.getJSONObject(i);val l=o.getJSONArray("lessons")
 Plan(o.getString("date"),o.optInt("target",240),(0 until l.length()).map{j->val x=l.getJSONObject(j);Lesson(x.getString("id"),x.getString("name"),x.getString("topic"),x.getString("start"),x.getString("end"),x.optLong("started"),x.optLong("finished"),x.optInt("tests"))},o.optBoolean("closed"))}
}.getOrDefault(emptyList())
private fun save(c:Context,ps:List<Plan>){val a=JSONArray();ps.forEach{p->val o=JSONObject().put("date",p.date).put("target",p.target).put("closed",p.closed);val l=JSONArray();p.lessons.forEach{x->l.put(JSONObject().put("id",x.id).put("name",x.name).put("topic",x.topic).put("start",x.start).put("end",x.end).put("started",x.started).put("finished",x.finished).put("tests",x.tests))};o.put("lessons",l);a.put(o)};c.getSharedPreferences(PREF,0).edit().putString("plans",a.toString()).apply()}
private fun fa(s:String)=s.map{if(it in '0'..'9')"۰۱۲۳۴۵۶۷۸۹"[it-'0'] else it}.joinToString("")
private fun jalali(d:LocalDate):String{
 var gy=d.year-1600;val gm=d.monthValue-1;val gd=d.dayOfMonth-1
 val gdM=intArrayOf(0,31,if(d.isLeapYear)29 else 28,31,30,31,30,31,31,30,31,30,31)
 var g=365*gy+(gy+3)/4-(gy+99)/100+(gy+399)/400+gd
 for(i in 0 until gm)g+=gdM[i+1]
 var j=g-79;val cycle=j/12053;j%=12053
 var jy=979+33*cycle+4*(j/1461);j%=1461
 if(j>=366){jy+=(j-1)/365;j=(j-1)%365}
 val jm=if(j<186)1+j/31 else 7+(j-186)/30
 val jd=if(j<186)1+j%31 else 1+(j-186)%30
 val m=listOf("فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور","مهر","آبان","آذر","دی","بهمن","اسفند")
 return "${fa(jd.toString())} ${m[jm-1]} ${fa(jy.toString())}"
}
private fun speakTime(s:String):String=runCatching{val t=LocalTime.parse(s);"ساعت ${t.hour} و ${t.minute} دقیقه"}.getOrDefault(s)
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);makeChannel(this);setContent{PaceApp()}}}
private fun makeChannel(c:Context){val n=c.getSystemService(NotificationManager::class.java);if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(NotificationChannel(CH,"یادآوری مطالعه",NotificationManager.IMPORTANCE_HIGH).apply{description="یادآوری صوتی درس‌ها";lockscreenVisibility=Notification.VISIBILITY_PUBLIC})}
class StudyAlarmReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val s=Intent(c,AnnounceService::class.java).putExtras(i);ContextCompat.startForegroundService(c,s)}}
class AnnounceService:Service(),TextToSpeech.OnInitListener{
 private var t:TextToSpeech?=null;private var msg=""
 override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{
  val title=i?.getStringExtra("lesson")?:"درس";val kind=i?.getStringExtra("kind")?:"start";val name=getSharedPreferences(PREF,0).getString("user","دوست من")?:"دوست من"
  val clock=i?.getStringExtra("clock")?:"";val lang=getSharedPreferences(PREF,0).getString("speech","fa")
  msg=if(kind=="end")"${name} خسته نباشی. ${clock}. زمان مطالعه ${title} تمام شد." else "${name}، ${clock}. برو درس ${title} را شروع کن."
  if(lang=="en")msg=if(kind=="end")"${name}, well done. Study time for ${title} is over." else "${name}, it is time to start ${title}."
  val open=PendingIntent.getActivity(this,3,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val n=NotificationCompat.Builder(this,CH).setSmallIcon(R.drawable.ic_amz_pace).setContentTitle(if(kind=="end")"پایان درس" else "زمان شروع درس").setContentText(msg).setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setContentIntent(open).setFullScreenIntent(open,true).build()
  startForeground(3,n);t=TextToSpeech(this,this);return START_NOT_STICKY
 }
 override fun onInit(status:Int){if(status==TextToSpeech.SUCCESS){t?.language=if(msg.contains("well done")||msg.contains("it is time"))Locale.US else Locale("fa","IR");val vol=getSharedPreferences(PREF,0).getFloat("volume",1f);t?.speak(msg,TextToSpeech.QUEUE_FLUSH,Bundle().apply{putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,vol)}, "pace");Handler(mainLooper).postDelayed({stopSelf()},9000)}}
 override fun onBind(i:Intent?)=null
 override fun onDestroy(){t?.stop();t?.shutdown();super.onDestroy()}
}
private fun scheduleStart(c:Context,l:Lesson,date:String){
 val am=c.getSystemService(AlarmManager::class.java)
 val time=LocalTime.parse(l.start)
 var at=LocalDate.parse(date).atTime(time).atZone(ZoneId.systemDefault())
 if(at.toInstant().toEpochMilli()<=System.currentTimeMillis() || l.started>0 || l.finished>0) return
 val intent=Intent(c,StudyAlarmReceiver::class.java)
  .putExtra("kind","start").putExtra("lesson",l.name).putExtra("clock",speakTime(l.start))
 val pi=PendingIntent.getBroadcast(c,l.id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 if(Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at.toInstant().toEpochMilli(),pi)
 else am.setExact(AlarmManager.RTC_WAKEUP,at.toInstant().toEpochMilli(),pi)
}
private fun scheduleEnd(c:Context,l:Lesson,date:String){
 val am=c.getSystemService(AlarmManager::class.java)
 val time=LocalTime.parse(l.end)
 var at=LocalDate.parse(date).atTime(time).atZone(ZoneId.systemDefault())
 if(at.toInstant().toEpochMilli()<=System.currentTimeMillis()) at=at.plusDays(1)
 val intent=Intent(c,StudyAlarmReceiver::class.java)
  .putExtra("kind","end").putExtra("lesson",l.name).putExtra("clock",speakTime(l.end))
 val pi=PendingIntent.getBroadcast(c,l.id.hashCode()+1,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 if(Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at.toInstant().toEpochMilli(),pi)
 else am.setExact(AlarmManager.RTC_WAKEUP,at.toInstant().toEpochMilli(),pi)
}
private fun hasAccess(c:Context):Boolean{
 val n=Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
 val a=Build.VERSION.SDK_INT<31||c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
 val f=Build.VERSION.SDK_INT<34||c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
 return n&&a&&f
}
@Composable fun PaceAppContent(){
 val c=LocalContext.current;val pref=c.getSharedPreferences(PREF,0)
 var plans by remember{mutableStateOf(load(c))};var user by remember{mutableStateOf(pref.getString("user","")?:"")}
 var page by remember{mutableIntStateOf(0)};var theme by remember{mutableStateOf(pref.getString("theme","dark")?:"dark")}
 var tick by remember{mutableIntStateOf(0)};LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(1000);tick++;plans=load(c)}}
 var name by remember{mutableStateOf(user)};var setup by remember{mutableStateOf(user.isBlank())}
 val ask=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
 val bg=if(theme=="light")Color(0xFFF1F4F7) else ink;val fg=if(theme=="light")ink else paper;val card=if(theme=="light")Color.White else Color(0xFF1B2B43)
 MaterialTheme(colorScheme=darkColorScheme(primary=teal,background=bg,surface=card,onSurface=fg)){
 Surface(Modifier.fillMaxSize(),color=bg){
 when{
 setup->Column(Modifier.fillMaxSize().padding(26.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Logo(90);Text("به AMZ Pace خوش آمدی",color=fg,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("با چه اسمی صدایت کنیم؟",color=muted);OutlinedTextField(name,{name=it},label={Text("نام شما")});Button(onClick={user=name.trim();pref.edit().putString("user",user).apply();setup=false},enabled=name.isNotBlank()){Text("ادامه")}}
 !hasAccess(c)->Column(Modifier.fillMaxSize().padding(22.dp),verticalArrangement=Arrangement.Center){Text("برای شروع باید دسترسی‌ها را فعال کنی",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("این مجوزها برای اعلان و زنگ دقیق درس‌ها استفاده می‌شوند.",color=muted);AccessRow("اعلان‌ها"){if(Build.VERSION.SDK_INT>=33)ask.launch(Manifest.permission.POST_NOTIFICATIONS)};AccessRow("آلارم دقیق"){if(Build.VERSION.SDK_INT>=31)c.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${c.packageName}")))};AccessRow("نمایش تمام‌صفحه"){if(Build.VERSION.SDK_INT>=34)c.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:${c.packageName}")))};Button(onClick={tick++}){Text("بررسی دوباره")}}
 else->Column{
 Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Logo(42);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text("AMZ PACE",color=fg,fontWeight=FontWeight.Black,letterSpacing=2.sp);Text("برنامه‌ریز مطالعه",color=muted,fontSize=12.sp)};Text(jalali(LocalDate.now()),color=muted,fontSize=12.sp)}
 AnimatedContent(page,label="page"){p->when(p){0->Today(plans,user,card,fg,{plan->plans=(plans.filterNot{it.date==plan.date}+plan);save(c,plans);plan.lessons.forEach{scheduleStart(c,it,plan.date)}},{plan->plans=(plans.filterNot{it.date==plan.date}+plan);save(c,plans)})
 1->Reports(plans,card,fg);2->CalendarView(plans,card,fg);else->Settings(c,card,fg,theme){theme=it;pref.edit().putString("theme",it).apply()}}}
 NavigationBar(containerColor=card){listOf("امروز","گزارش","تقویم","تنظیمات").forEachIndexed{i,s->NavigationBarItem(page==i,{page=i},{Icon(listOf(Icons.Default.Today,Icons.Default.BarChart,Icons.Default.CalendarMonth,Icons.Default.Settings)[i],null)},label={Text(s)},colors=NavigationBarItemDefaults.colors(selectedIconColor=teal,selectedTextColor=teal,indicatorColor=teal.copy(alpha=.15f)))}}
 }
 }
 }
 }
}
@Composable private fun Logo(size:Int){Box(Modifier.size(size.dp).clip(RoundedCornerShape((size/3).dp)).background(ink),contentAlignment=Alignment.Center){Canvas(Modifier.fillMaxSize(.68f)){drawCircle(teal,style=Stroke(size.dp.toPx()*.09f));drawLine(paper,center,androidx.compose.ui.geometry.Offset(center.x,center.y*.52f),size.dp.toPx()*.08f,cap=StrokeCap.Round);drawLine(paper,center,androidx.compose.ui.geometry.Offset(center.x+size.dp.toPx()*.2f,center.y+size.dp.toPx()*.12f),size.dp.toPx()*.08f,cap=StrokeCap.Round)}}}
@Composable private fun AccessRow(s:String,act:()->Unit){Card(Modifier.fillMaxWidth().padding(vertical=5.dp)){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(s,Modifier.weight(1f));TextButton(onClick=act){Text("فعال‌سازی")}}}}
@Composable private fun Today(plans:List<Plan>,user:String,card:Color,fg:Color,onSave:(Plan)->Unit,onUpdate:(Plan)->Unit){
 val context=LocalContext.current
 var create by remember{mutableStateOf(false)};var finish by remember{mutableStateOf<Lesson?>(null)};var yes by remember{mutableStateOf(false)};var count by remember{mutableStateOf("0")}
 val p=plans.firstOrNull{it.date==LocalDate.now().toString()};val done=p?.lessons?.count{it.finished>0}?:0
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
 item{Card(colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(18.dp)){Text("سلام ${user}، آماده‌ای؟",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("با تمرکز پیش برو؛ استراحت را هم فراموش نکن.",color=muted);Spacer(Modifier.height(10.dp));Text("پیشرفت امروز: ${done}/${p?.lessons?.size?:0} درس",color=teal,fontWeight=FontWeight.Bold)}}}
 item{Row(verticalAlignment=Alignment.CenterVertically){Text("برنامه امروز",color=fg,fontSize=18.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Button(onClick={create=true}){Icon(Icons.Default.Add,null);Text("برنامه جدید")}}}
 if(p==null)item{Text("هنوز برنامه‌ای نساختی.",color=muted)}
 if(p!=null){
 item{Card(colors=CardDefaults.cardColors(containerColor=card)){Row(Modifier.fillMaxWidth().padding(15.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("هدف: ${p.target} دقیقه",color=fg);Text("ثبت‌شده: ${p.minutes} دقیقه",color=teal)}}}
 items(p.lessons){l->Card(colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(14.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(l.name,color=fg,fontWeight=FontWeight.Bold);Text(l.topic,color=muted,fontSize=12.sp)};Text("${fa(l.start)}–${fa(l.end)}",color=teal)}
 when{l.finished>0->Text("تمام شد · ${l.actual} دقیقه · ${l.tests} تست",color=teal)
 l.started>0->Button(onClick={finish=l;yes=false;count="0"},modifier=Modifier.fillMaxWidth()){Text("پایان درس و ثبت تست")}
 LocalTime.now()>=LocalTime.parse(l.start)->Button(onClick={
 val started=System.currentTimeMillis()
 onUpdate(p.copy(lessons=p.lessons.map{if(it.id==l.id)it.copy(started=started) else it}))
 scheduleEnd(context,l,p.date)
},modifier=Modifier.fillMaxWidth()){Text("شروع مطالعه")}
 else->Text("شروع این درس ساعت "+fa(l.start)+" فعال می‌شود",color=muted,fontSize=12.sp)}
 }}}
 if(p.lessons.all{it.finished>0})item{Card(colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(16.dp)){val delta=p.minutes-p.target;Text("تحلیل امروز",color=fg,fontWeight=FontWeight.Bold,fontSize=18.sp);Text(if(delta>=0)"به هدف رسیدی؛ ${delta} دقیقه بیشتر!" else "${-delta} دقیقه تا هدف فاصله داشتی.",color=teal);Text("مجموع تست: ${p.tests}",color=fg);p.lessons.forEach{Text("${it.name}: ${it.actual} دقیقه، ${it.tests} تست",color=muted,fontSize=12.sp)};Bars(p.lessons.map{it.name to it.actual.toFloat()},teal)}}}
 }
 }
 if(create)CreateDialog(p,{create=false},onSave)
 if(finish!=null&&p!=null)AlertDialog(onDismissRequest={finish=null},title={Text("تست این درس را زدی؟")},text={Column{Row{FilterChip(yes,{yes=true},label={Text("بله")});Spacer(Modifier.width(8.dp));FilterChip(!yes,{yes=false},label={Text("خیر")})};if(yes)OutlinedTextField(count,{count=it.filter(Char::isDigit)},label={Text("تعداد تست")})}},confirmButton={TextButton(onClick={val l=finish!!;onUpdate(p.copy(lessons=p.lessons.map{if(it.id==l.id)it.copy(finished=System.currentTimeMillis(),tests=if(yes)count.toIntOrNull()?:0 else 0)else it},closed=p.lessons.all{it.id==l.id||it.finished>0}));finish=null}){Text("ثبت")}})
}
@Composable
private fun CreateDialog(old: Plan?, close: () -> Unit, savePlan: (Plan) -> Unit) {
    val context = LocalContext.current
    var target by remember { mutableStateOf((old?.target ?: 240).toString()) }
    var lessons by remember {
        mutableStateOf(old?.lessons ?: listOf(Lesson(name = "", topic = "", start = "09:00", end = "10:00")))
    }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("ساخت برنامه") },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it.filter(Char::isDigit) },
                    label = { Text("هدف مطالعه به دقیقه") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(lessons, key = { it.id }) { lesson ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                OutlinedTextField(
                                    value = lesson.name,
                                    onValueChange = { value ->
                                        lessons = lessons.map { if (it.id == lesson.id) it.copy(name = value) else it }
                                    },
                                    label = { Text("نام درس") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = lesson.topic,
                                    onValueChange = { value ->
                                        lessons = lessons.map { if (it.id == lesson.id) it.copy(topic = value) else it }
                                    },
                                    label = { Text("موضوع") },
                                    singleLine = true
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        chooseTime(context, lesson.start) { value ->
                                            lessons = lessons.map { if (it.id == lesson.id) it.copy(start = value) else it }
                                        }
                                    }) { Text("شروع ${lesson.start}") }
                                    TextButton(onClick = {
                                        chooseTime(context, lesson.end) { value ->
                                            lessons = lessons.map { if (it.id == lesson.id) it.copy(end = value) else it }
                                        }
                                    }) { Text("پایان ${lesson.end}") }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = {
                                        if (lessons.size > 1) lessons = lessons.filterNot { it.id == lesson.id }
                                    }) { Icon(Icons.Default.Delete, contentDescription = "حذف درس") }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    lessons = lessons + Lesson(name = "", topic = "", start = "10:15", end = "11:15")
                }) { Text("+ افزودن درس") }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val sorted = lessons.sortedBy { it.start }
                val targetValue = target.toIntOrNull()
                val validTimes = sorted.all { lesson ->
                    runCatching {
                        val startTime = LocalTime.parse(lesson.start)
                        val endTime = LocalTime.parse(lesson.end)
                        lesson.name.isNotBlank() && lesson.topic.isNotBlank() && endTime.isAfter(startTime)
                    }.getOrDefault(false)
                }
                val noOverlap = sorted.zipWithNext().all { (first, second) ->
                    runCatching { !LocalTime.parse(first.end).isAfter(LocalTime.parse(second.start)) }.getOrDefault(false)
                }
                if (targetValue != null && targetValue > 0 && validTimes && noOverlap) {
                    savePlan(Plan(date = old?.date ?: LocalDate.now().toString(), target = targetValue, lessons = sorted))
                    close()
                } else {
                    error = "نام و موضوع درس، ساعت‌ها، هدف و نبود تداخل را بررسی کن."
                }
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = close) { Text("انصراف") } }
    )
}
private fun chooseTime(c:Context,initial:String,done:(String)->Unit){val t=runCatching{LocalTime.parse(initial)}.getOrDefault(LocalTime.NOON);TimePickerDialog(c,{_,h,m->done("%02d:%02d".format(h,m))},t.hour,t.minute,true).show()}
@Composable private fun Bars(data:List<Pair<String,Float>>,color:Color){if(data.isEmpty())Text("داده‌ای ثبت نشده",color=muted)else{val max=data.maxOf{it.second}.coerceAtLeast(1f);data.forEach{(n,v)->Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(vertical=4.dp)){Text(n,color=muted,modifier=Modifier.width(65.dp),fontSize=11.sp);Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(color.copy(alpha=.16f))){Box(Modifier.fillMaxWidth((v/max).coerceIn(0f,1f)).fillMaxHeight().background(color))};Text(v.toInt().toString(),color=muted,modifier=Modifier.width(28.dp),textAlign=TextAlign.End,fontSize=11.sp)}}}}
@Composable private fun Reports(plans:List<Plan>,card:Color,fg:Color){
 var range by remember{mutableStateOf("هفتگی")};val n=when(range){"روزانه"->1;"هفتگی"->7;"ماهانه"->30;else->90};val from=LocalDate.now().minusDays((n-1).toLong());val list=plans.filter{runCatching{!LocalDate.parse(it.date).isBefore(from)}.getOrDefault(false)}
 val lessons=list.flatMap{it.lessons};val byTime=lessons.groupBy{it.name}.mapValues{it.value.sumOf{x->x.actual}.toFloat()};val byTest=lessons.groupBy{it.name}.mapValues{it.value.sumOf{x->x.tests}.toFloat()}
 Column(Modifier.fillMaxSize().padding(16.dp)){Text("گزارش عملکرد",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold);Row{listOf("روزانه","هفتگی","ماهانه","سه‌ماهه").forEach{FilterChip(range==it,{range=it},label={Text(it)})}};Text("مطالعه: ${lessons.sumOf{it.actual}} دقیقه · تست: ${lessons.sumOf{it.tests}}",color=teal);LazyColumn{item{Card(Modifier.padding(vertical=8.dp)){Column(Modifier.padding(14.dp)){Text("زمان مطالعه هر درس",color=fg);Bars(byTime.toList(),teal)}}};item{Card{Column(Modifier.padding(14.dp)){Text("تعداد تست هر درس",color=fg);Bars(byTest.toList(),Color(0xFFF2B66D))}}};items(list.sortedByDescending{it.date}){p->Text("${jalali(LocalDate.parse(p.date))} · ${p.minutes} دقیقه · ${p.tests} تست",color=muted,modifier=Modifier.padding(8.dp))}}}
}
@Composable private fun CalendarView(plans:List<Plan>,card:Color,fg:Color){
 var month by remember{mutableStateOf(LocalDate.now().withDayOfMonth(1))};var selected by remember{mutableStateOf(LocalDate.now())};val p=plans.firstOrNull{it.date==selected.toString()}
 Column(Modifier.fillMaxSize().padding(16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("تقویم شمسی",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={month=month.minusMonths(1)}){Icon(Icons.Default.ChevronLeft,null,tint=fg)};IconButton(onClick={month=month.plusMonths(1)}){Icon(Icons.Default.ChevronRight,null,tint=fg)}};Text(jalali(month),color=teal)
 val offset=(month.dayOfWeek.value+1)%7
 Row{listOf("ش","ی","د","س","چ","پ","ج").forEach{Text(it,Modifier.weight(1f),color=muted,textAlign=TextAlign.Center)}}
 for(w in 0..5)Row{for(col in 0..6){val day=w*7+col-offset+1;Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(8.dp)).background(if(day in 1..month.lengthOfMonth()&&selected.dayOfMonth==day&&selected.month==month.month)teal else card).clickable(day in 1..month.lengthOfMonth()){selected=month.withDayOfMonth(day)},contentAlignment=Alignment.Center){if(day in 1..month.lengthOfMonth())Column(horizontalAlignment=Alignment.CenterHorizontally){Text(fa(jalali(month.withDayOfMonth(day)).substringBefore(" ")),color=if(selected.dayOfMonth==day&&selected.month==month.month)ink else fg);if(plans.any{it.date==month.withDayOfMonth(day).toString()})Box(Modifier.size(4.dp).background(teal,CircleShape))}}}}
 Card(Modifier.fillMaxWidth().weight(1f)){Column(Modifier.padding(14.dp)){Text(jalali(selected),color=fg,fontWeight=FontWeight.Bold);if(p==null)Text("گزارشی برای این روز نیست.",color=muted)else{Text("${p.minutes} دقیقه مطالعه · ${p.tests} تست",color=teal);p.lessons.forEach{Text("${it.name} — ${it.topic}: ${it.actual} دقیقه، ${it.tests} تست",color=muted,fontSize=12.sp)}}}}
 }
}
@Composable private fun Settings(c:Context,card:Color,fg:Color,theme:String,setTheme:(String)->Unit){
 val pref=c.getSharedPreferences(PREF,0);var speech by remember{mutableStateOf(pref.getString("speech","fa")?:"fa")};var volume by remember{mutableFloatStateOf(pref.getFloat("volume",1f))}
 Column(Modifier.fillMaxSize().padding(16.dp)){Text("تنظیمات",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold);Card(Modifier.padding(top=12.dp)){Column(Modifier.padding(16.dp)){Text("پوسته",color=fg);Row{listOf("dark" to "تیره","light" to "روشن").forEach{FilterChip(theme==it.first,{setTheme(it.first)},label={Text(it.second)})}};Divider();Text("زبان گفتار",color=fg);Row{FilterChip(speech=="fa",{speech="fa";pref.edit().putString("speech",speech).apply()},label={Text("فارسی")});FilterChip(speech=="en",{speech="en";pref.edit().putString("speech",speech).apply()},label={Text("English")})};Text("بلندی صدا: ${(volume*100).toInt()}٪",color=fg);Slider(volume,{volume=it},onValueChangeFinished={pref.edit().putFloat("volume",volume).apply()});Text("AMZ Pace",color=teal,fontWeight=FontWeight.Bold,fontSize=18.sp);Text("ساخته شده توسط امین",color=muted)}}}
}
