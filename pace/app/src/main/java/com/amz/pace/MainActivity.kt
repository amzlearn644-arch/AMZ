
package com.amz.pace

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.animateContentSize
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
data class Plan(val id:String=UUID.randomUUID().toString(),val date:String=LocalDate.now().toString(),val target:Int=240,val lessons:List<Lesson>,val closed:Boolean=false){
 val minutes:Int get()=lessons.sumOf{it.actual}; val tests:Int get()=lessons.sumOf{it.tests}
}
data class StudyBreak(val start:String,val end:String,val minutes:Int)
internal fun studyBreaks(lessons:List<Lesson>):List<StudyBreak>{
 val sorted=lessons.sortedBy{runCatching{LocalTime.parse(it.start)}.getOrDefault(LocalTime.MIN)}
 return sorted.zipWithNext().mapNotNull{(a,b)->runCatching{val from=LocalTime.parse(a.end);val to=LocalTime.parse(b.start);val gap=Duration.between(from,to).toMinutes().toInt();if(gap>0)StudyBreak(a.end,b.start,gap)else null}.getOrNull()}
}
internal fun isValidPlan(target:Int,lessons:List<Lesson>):Boolean{
 if(target<=0||lessons.isEmpty())return false
 val sorted=lessons.sortedBy{it.start}
 val valid=sorted.all{l->runCatching{l.name.isNotBlank()&&l.topic.isNotBlank()&&LocalTime.parse(l.end).isAfter(LocalTime.parse(l.start))}.getOrDefault(false)}
 return valid&&sorted.zipWithNext().all{(a,b)->runCatching{!LocalTime.parse(a.end).isAfter(LocalTime.parse(b.start))}.getOrDefault(false)}
}
private fun load(c:Context):List<Plan> = runCatching{
 val a=JSONArray(c.getSharedPreferences(PREF,0).getString("plans","[]"))
 (0 until a.length()).map{ i-> val o=a.getJSONObject(i);val l=o.getJSONArray("lessons")
 Plan(o.optString("id",UUID.randomUUID().toString()),o.getString("date"),o.optInt("target",240),(0 until l.length()).map{j->val x=l.getJSONObject(j);Lesson(x.getString("id"),x.getString("name"),x.getString("topic"),x.getString("start"),x.getString("end"),x.optLong("started"),x.optLong("finished"),x.optInt("tests"))},o.optBoolean("closed"))}
}.getOrDefault(emptyList())
private fun save(c:Context,ps:List<Plan>){val a=JSONArray();ps.forEach{p->val o=JSONObject().put("id",p.id).put("date",p.date).put("target",p.target).put("closed",p.closed);val l=JSONArray();p.lessons.forEach{x->l.put(JSONObject().put("id",x.id).put("name",x.name).put("topic",x.topic).put("start",x.start).put("end",x.end).put("started",x.started).put("finished",x.finished).put("tests",x.tests))};o.put("lessons",l);a.put(o)};c.getSharedPreferences(PREF,0).edit().putString("plans",a.toString()).apply()}
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
internal data class JalaliParts(val year:Int,val month:Int,val day:Int)
internal fun jalaliParts(date:LocalDate):JalaliParts {
    var gy = date.year - 1600
    val gm = date.monthValue - 1
    val gd = date.dayOfMonth - 1
    val monthDays = intArrayOf(0,31,if(date.isLeapYear)29 else 28,31,30,31,30,31,31,30,31,30,31)
    var days = 365*gy + (gy+3)/4 - (gy+99)/100 + (gy+399)/400 + gd
    for (i in 0 until gm) days += monthDays[i+1]
    var j = days - 79
    val cycle = j / 12053
    j %= 12053
    var jy = 979 + 33*cycle + 4*(j/1461)
    j %= 1461
    if (j >= 366) { jy += (j-1)/365; j = (j-1)%365 }
    val jm = if (j < 186) 1 + j/31 else 7 + (j-186)/30
    val jd = if (j < 186) 1 + j%31 else 1 + (j-186)%30
    return JalaliParts(jy,jm,jd)
}
internal fun jalaliToGregorian(year:Int,month:Int,day:Int):LocalDate {
    var jy = year - 979
    var days = 365*jy + (jy/33)*8 + ((jy%33+3)/4)
    days += if (month <= 6) (month-1)*31 else 186 + (month-7)*30
    days += day - 1
    days += 79
    var gy = 1600 + 400*(days/146097)
    days %= 146097
    var leap = true
    if (days >= 36525) {
        days--
        gy += 100*(days/36524)
        days %= 36524
        if (days >= 365) days++ else leap = false
    }
    gy += 4*(days/1461)
    days %= 1461
    if (days >= 366) {
        leap = false
        days--
        gy += days/365
        days %= 365
    }
    val monthDays = intArrayOf(31, if(leap)29 else 28,31,30,31,30,31,31,30,31,30,31)
    var gm = 0
    while (gm < 12 && days >= monthDays[gm]) { days -= monthDays[gm]; gm++ }
    return LocalDate.of(gy,gm+1,days+1)
}
internal fun jalaliMonthLength(year:Int,month:Int):Int =
    if (month <= 6) 31 else if (month <= 11) 30 else {
        val last = jalaliToGregorian(year,12,30)
        if (jalaliParts(last).month == 12 && jalaliParts(last).day == 30) 30 else 29
    }
private val jalaliMonths=listOf("فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور","مهر","آبان","آذر","دی","بهمن","اسفند")
private val faUnits=listOf("صفر","یک","دو","سه","چهار","پنج","شش","هفت","هشت","نه","ده","یازده","دوازده","سیزده","چهارده","پانزده","شانزده","هفده","هجده","نوزده")
private val faTens=listOf("","","بیست","سی","چهل","پنجاه")
private fun persianNumber(n:Int):String{
 if(n<20)return faUnits[n.coerceAtLeast(0)]
 val ten=n/10;val unit=n%10
 return if(unit==0)faTens[ten] else faTens[ten]+" و "+faUnits[unit]
}
internal fun speakTime(s:String):String=runCatching{val t=LocalTime.parse(s);"ساعت ${persianNumber(t.hour)} و ${persianNumber(t.minute)} دقیقه"}.getOrDefault(s)
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);makeChannel(this);setContent{PaceApp()}};override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);recreate()}}
private fun makeChannel(c:Context){val n=c.getSystemService(NotificationManager::class.java);if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(NotificationChannel(CH,"یادآوری مطالعه",NotificationManager.IMPORTANCE_HIGH).apply{description="یادآوری صوتی درس‌ها";lockscreenVisibility=Notification.VISIBILITY_PUBLIC})}
class StudyAlarmReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val s=Intent(c,AnnounceService::class.java).putExtras(i);ContextCompat.startForegroundService(c,s)}}
class PaceBootReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent?){if(i?.action!=Intent.ACTION_BOOT_COMPLETED)return;load(c).forEach{p->p.lessons.forEach{l->runCatching{if(l.started>0&&l.finished==0L)scheduleEnd(c,l,p.date,p.id)else scheduleStart(c,l,p.date)}}}}}
class AnnounceService:Service(),TextToSpeech.OnInitListener{
 private var t:TextToSpeech?=null;private var msg=""
 override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{
  val title=i?.getStringExtra("lesson")?:"درس";val kind=i?.getStringExtra("kind")?:"start";val name=getSharedPreferences(PREF,0).getString("user","دوست من")?:"دوست من"
  val clock=i?.getStringExtra("clock")?:"";val lang=getSharedPreferences(PREF,0).getString("speech","fa")
  msg=if(kind=="end")"${name} خسته نباشی. ${clock}. زمان مطالعه ${title} تمام شد." else "${name}، ${clock}. برو درس ${title} را شروع کن."
  if(lang=="en")msg=if(kind=="end")"${name}, well done. Study time for ${title} is over." else "${name}, it is time to start ${title}."
  val open=PendingIntent.getActivity(this,3,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("kind",kind).putExtra("lessonId",i?.getStringExtra("lessonId")).putExtra("date",i?.getStringExtra("date")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val n=NotificationCompat.Builder(this,CH).setSmallIcon(R.drawable.ic_amz_pace).setContentTitle(if(kind=="end")"پایان درس" else "زمان شروع درس").setContentText(msg).setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setContentIntent(open).setFullScreenIntent(open,true).build()
  startForeground(3,n);t=TextToSpeech(this,this);return START_NOT_STICKY
 }
 override fun onInit(status:Int){
  if(status!=TextToSpeech.SUCCESS){stopSelf();return}
  val engine=t?:run{stopSelf();return}
  engine.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
   override fun onStart(utteranceId:String?){}
   override fun onDone(utteranceId:String?){stopSelf()}
   override fun onError(utteranceId:String?){stopSelf()}
  })
  engine.language=if(msg.contains("well done")||msg.contains("it is time"))Locale.US else Locale("fa","IR")
  val vol=getSharedPreferences(PREF,0).getFloat("volume",1f)
  val result=engine.speak(msg,TextToSpeech.QUEUE_FLUSH,Bundle().apply{putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,vol)},"pace")
  if(result==TextToSpeech.ERROR)stopSelf() else Handler(mainLooper).postDelayed({stopSelf()},30000)
 }
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
private fun scheduleEnd(c:Context,l:Lesson,date:String,planId:String){
 val am=c.getSystemService(AlarmManager::class.java)
 val time=LocalTime.parse(l.end)
 var at=LocalDate.parse(date).atTime(time).atZone(ZoneId.systemDefault())
 if(at.toInstant().toEpochMilli()<=System.currentTimeMillis()) return
 val intent=Intent(c,StudyAlarmReceiver::class.java)
  .putExtra("kind","end").putExtra("lesson",l.name).putExtra("lessonId",l.id).putExtra("date",date).putExtra("planId",planId).putExtra("clock",speakTime(l.end))
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
 var permissionRefresh by remember{mutableIntStateOf(0)}
 DisposableEffect(c){val owner=c as? LifecycleOwner;val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)permissionRefresh++};owner?.lifecycle?.addObserver(observer);onDispose{owner?.lifecycle?.removeObserver(observer)}}
 val notificationGranted = permissionRefresh>=0 && (Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
 val exactAlarmGranted = permissionRefresh>=0 && (Build.VERSION.SDK_INT<31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
 val fullScreenGranted = permissionRefresh>=0 && (Build.VERSION.SDK_INT<34 || c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
 val allPermissionsGranted = notificationGranted && exactAlarmGranted && fullScreenGranted
 var name by remember{mutableStateOf(user)};var setup by remember{mutableStateOf(user.isBlank())}
 val ask=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
 val bg=if(theme=="light")Color(0xFFF1F4F7) else ink;val fg=if(theme=="light")ink else paper;val card=if(theme=="light")Color.White else Color(0xFF1B2B43)
 MaterialTheme(colorScheme=darkColorScheme(primary=teal,background=bg,surface=card,onSurface=fg)){
 Surface(Modifier.fillMaxSize(),color=bg){
 when{
 !allPermissionsGranted->Column(Modifier.fillMaxSize().padding(horizontal=22.dp, vertical=18.dp),verticalArrangement=Arrangement.Center){
  Logo(76); Spacer(Modifier.height(18.dp)); Text("آماده‌سازی AMZ Pace",color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold)
  Text("برای اینکه یادآوری درس‌ها درست و به‌موقع کار کند، دسترسی‌های زیر را فعال کن.",color=muted); Spacer(Modifier.height(16.dp))
  AccessRow("اعلان‌ها","برای نمایش یادآوری درس‌ها",notificationGranted){if(Build.VERSION.SDK_INT>=33)ask.launch(Manifest.permission.POST_NOTIFICATIONS)}
  AccessRow("آلارم دقیق","برای اجرای یادآوری در ساعت تعیین‌شده",exactAlarmGranted){if(Build.VERSION.SDK_INT>=31)c.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+c.packageName)))}
  AccessRow("نمایش تمام‌صفحه","برای نمایش هشدار مهم هنگام شروع درس",fullScreenGranted){if(Build.VERSION.SDK_INT>=34)c.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+c.packageName)))}
  Spacer(Modifier.height(12.dp)); Text("پس از بازگشت از تنظیمات، وضعیت هر دسترسی خودکار بررسی می‌شود.",color=muted,fontSize=12.sp)
 }
 setup->Column(Modifier.fillMaxSize().padding(26.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Logo(90);Text("به AMZ Pace خوش آمدی",color=fg,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("با چه اسمی صدایت کنیم؟",color=muted);OutlinedTextField(name,{name=it},label={Text("نام شما")});Button(onClick={user=name.trim();pref.edit().putString("user",user).apply();setup=false},enabled=name.isNotBlank()){Text("ادامه")}}
 else->Column{
 Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Logo(42);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text("AMZ PACE",color=fg,fontWeight=FontWeight.Black,letterSpacing=2.sp);Text("برنامه‌ریز مطالعه",color=muted,fontSize=12.sp)};Text(jalali(LocalDate.now()),color=muted,fontSize=12.sp)}
 AnimatedContent(page,label="page"){p->when(p){0->Today(plans,user,card,fg,{plan->plans=(plans.filterNot{it.id==plan.id}+plan);save(c,plans);plan.lessons.forEach{scheduleStart(c,it,plan.date)}},{plan->plans=(plans.filterNot{it.id==plan.id}+plan);save(c,plans)})
 1->Reports(plans,card,fg);2->CalendarView(plans,card,fg);else->Settings(c,card,fg,theme){theme=it;pref.edit().putString("theme",it).apply()}}}
 NavigationBar(containerColor=card){listOf("امروز","گزارش","تقویم","تنظیمات").forEachIndexed{i,s->NavigationBarItem(page==i,{page=i},{Icon(listOf(Icons.Default.Today,Icons.Default.BarChart,Icons.Default.CalendarMonth,Icons.Default.Tune)[i],null)},label={Text(s)},colors=NavigationBarItemDefaults.colors(selectedIconColor=teal,selectedTextColor=teal,indicatorColor=teal.copy(alpha=.15f)))}}
 }
 }
 }
 }
}
@Composable private fun Logo(size:Int){Box(Modifier.size(size.dp).clip(RoundedCornerShape((size/3).dp)).background(ink),contentAlignment=Alignment.Center){Canvas(Modifier.fillMaxSize(.68f)){drawCircle(teal,style=Stroke(size.dp.toPx()*.09f));drawLine(paper,center,androidx.compose.ui.geometry.Offset(center.x,center.y*.52f),size.dp.toPx()*.08f,cap=StrokeCap.Round);drawLine(paper,center,androidx.compose.ui.geometry.Offset(center.x+size.dp.toPx()*.2f,center.y+size.dp.toPx()*.12f),size.dp.toPx()*.08f,cap=StrokeCap.Round)}}}
@Composable private fun AccessRow(title:String,description:String,granted:Boolean,act:()->Unit){
 Card(Modifier.fillMaxWidth().padding(vertical=5.dp).animateContentSize(),colors=CardDefaults.cardColors(containerColor=if(granted)Color(0xFF123B3A) else Color(0xFF1B2B43)),shape=RoundedCornerShape(16.dp)){
  Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){Text(title,color=Color(0xFFF4F1E8),fontWeight=FontWeight.SemiBold);Text(if(granted)"دسترسی فعال است" else description,color=if(granted)Color(0xFF72E6B5) else muted,fontSize=11.sp)}
   AnimatedVisibility(visible=granted,enter=fadeIn()+scaleIn()){Icon(Icons.Default.CheckCircle,contentDescription="فعال",tint=Color(0xFF35D399),modifier=Modifier.size(30.dp))}
   if(!granted)TextButton(onClick=act){Text("فعال‌سازی",color=teal)}
  }
 }
}
@Composable private fun Today(plans:List<Plan>,user:String,card:Color,fg:Color,onSave:(Plan)->Unit,onUpdate:(Plan)->Unit){
 val context=LocalContext.current
 var create by remember{mutableStateOf(false)};var finish by remember{mutableStateOf<Lesson?>(null)};var yes by remember{mutableStateOf(false)};var count by remember{mutableStateOf("0")}
 var selectedPlanId by remember { mutableStateOf(plans.firstOrNull{it.date==LocalDate.now().toString()}?.id ?: "") };val todaysPlans=plans.filter{it.date==LocalDate.now().toString()};if(todaysPlans.none{it.id==selectedPlanId})selectedPlanId=todaysPlans.firstOrNull()?.id ?: "";val p=todaysPlans.firstOrNull{it.id==selectedPlanId};val done=p?.lessons?.count{it.finished>0}?:0
 LaunchedEffect(p?.lessons){val activity=context as? ComponentActivity;val intent=activity?.intent;if(intent?.getStringExtra("kind")=="end"){val planId=intent.getStringExtra("planId");val alarmPlan=plans.firstOrNull{it.id==planId} ?: p;if(alarmPlan!=null)selectedPlanId=alarmPlan.id;val id=intent.getStringExtra("lessonId");val lesson=alarmPlan?.lessons?.firstOrNull{it.id==id&&it.finished==0L};if(lesson!=null){finish=lesson;yes=false;count="0"};intent.removeExtra("kind");intent.removeExtra("lessonId");intent.removeExtra("date");intent.removeExtra("planId")}}
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
 item{Card(colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(18.dp)){Text("سلام ${user}، آماده‌ای؟",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("با تمرکز پیش برو؛ استراحت را هم فراموش نکن.",color=muted);Spacer(Modifier.height(10.dp));Text("پیشرفت امروز: ${done}/${p?.lessons?.size?:0} درس",color=teal,fontWeight=FontWeight.Bold)}}}
 item{Row(verticalAlignment=Alignment.CenterVertically){Text("برنامه امروز",color=fg,fontSize=18.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Button(onClick={create=true}){Icon(Icons.Default.Add,null);Text("برنامه جدید")}}}
 if(p==null)item{Text("هنوز برنامه‌ای نساختی.",color=muted)}
 if(p!=null){
 item{Card(colors=CardDefaults.cardColors(containerColor=card)){Row(Modifier.fillMaxWidth().padding(15.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("هدف: ${p.target} دقیقه",color=fg);Text("ثبت‌شده: ${p.minutes} دقیقه",color=teal)}}}
 val gaps=studyBreaks(p.lessons)
 if(gaps.isNotEmpty())item{Card(colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(14.dp)){Text("استراحت‌های بین درس‌ها",color=teal,fontWeight=FontWeight.Bold);gaps.forEach{g->Text("${fa(g.start)} تا ${fa(g.end)}  ·  ${fa(g.minutes.toString())} دقیقه استراحت",color=muted,fontSize=12.sp,modifier=Modifier.padding(top=5.dp))}}}}
 items(p.lessons){l->Card(colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(14.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(l.name,color=fg,fontWeight=FontWeight.Bold);Text(l.topic,color=muted,fontSize=12.sp)};Text("${fa(l.start)}–${fa(l.end)}",color=teal)}
 when{l.finished>0->Text("تمام شد · ${l.actual} دقیقه · ${l.tests} تست",color=teal)
 l.started>0->Button(onClick={finish=l;yes=false;count="0"},modifier=Modifier.fillMaxWidth()){Text("پایان درس و ثبت تست")}
 LocalTime.now()>=LocalTime.parse(l.start)&&LocalTime.now()<LocalTime.parse(l.end)->Button(onClick={
 val started=System.currentTimeMillis()
 onUpdate(p.copy(lessons=p.lessons.map{if(it.id==l.id)it.copy(started=started) else it}))
 scheduleEnd(context,l,p.date,p.id)
},modifier=Modifier.fillMaxWidth()){Text("شروع مطالعه")}
 LocalTime.now()>=LocalTime.parse(l.end)->Text("زمان این بازه گذشته است؛ برای ثبت مطالعه، بازه را در برنامه اصلاح کن.",color=muted,fontSize=12.sp)
 else->Text("شروع این درس ساعت "+fa(l.start)+" فعال می‌شود",color=muted,fontSize=12.sp)}
 }}}
 if(p.lessons.all{it.finished>0})item{Card(colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(16.dp)){val delta=p.minutes-p.target;Text("تحلیل امروز",color=fg,fontWeight=FontWeight.Bold,fontSize=18.sp);Text(if(delta>=0)"به هدف رسیدی؛ ${delta} دقیقه بیشتر!" else "${-delta} دقیقه تا هدف فاصله داشتی.",color=teal);Text("مجموع تست: ${p.tests}",color=fg);p.lessons.forEach{Text("${it.name}: ${it.actual} دقیقه، ${it.tests} تست",color=muted,fontSize=12.sp)};Bars(p.lessons.map{it.name to it.actual.toFloat()},teal)}}}
 }
 }
 if(create)CreateDialog(null,{create=false},onSave)
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
                if (targetValue != null && isValidPlan(targetValue, sorted)) {
                    savePlan(Plan(id = old?.id ?: UUID.randomUUID().toString(), date = old?.date ?: LocalDate.now().toString(), target = targetValue, lessons = sorted))
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
 var range by remember{mutableStateOf("هفتگی")}
 val today=LocalDate.now();val days=when(range){"روزانه"->1;"هفتگی"->7;"ماهانه"->30;else->90};val from=today.minusDays((days-1).toLong())
 val scoped=plans.filter{runCatching{val d=LocalDate.parse(it.date);!d.isBefore(from)&&!d.isAfter(today)}.getOrDefault(false)}
 val byDay=(0 until days).map{i->val d=from.plusDays(i.toLong());val mins=scoped.filter{it.date==d.toString()}.sumOf{it.minutes};val jp=jalaliParts(d);"${jp.day}/${jp.month}" to mins.toFloat()}
 val tests=scoped.flatMap{it.lessons}.groupBy{it.name}.mapValues{(_,v)->v.sumOf{it.tests}.toFloat()}.toList().sortedByDescending{it.second}
 val total=scoped.sumOf{it.minutes};val testTotal=scoped.sumOf{it.tests}
 Column(Modifier.fillMaxSize().padding(16.dp)){
  Text("گزارش عملکرد",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("روزانه","هفتگی","ماهانه","سه‌ماهه").forEach{FilterChip(range==it,{range=it},label={Text(it)})}}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   Card(Modifier.weight(1f),colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(12.dp)){Text("زمان مطالعه",color=muted,fontSize=12.sp);Text("${fa(total.toString())} دقیقه",color=teal,fontSize=19.sp,fontWeight=FontWeight.Bold)}}
   Card(Modifier.weight(1f),colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(12.dp)){Text("تعداد تست",color=muted,fontSize=12.sp);Text(fa(testTotal.toString()),color=Color(0xFFF2B66D),fontSize=19.sp,fontWeight=FontWeight.Bold)}}
  }
  LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){
   item{Card(colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(14.dp)){Text("روند زمان مطالعه روزانه (دقیقه)",color=fg);Bars(byDay.filter{it.second>0f},teal)}}}
   item{Card(colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(14.dp)){Text("تعداد تست به تفکیک درس",color=fg);Bars(tests,Color(0xFFF2B66D))}}}
   items(scoped.sortedByDescending{it.date}){p->Text("${jalali(LocalDate.parse(p.date))} · ${fa(p.minutes.toString())} دقیقه · ${fa(p.tests.toString())} تست",color=muted,modifier=Modifier.padding(8.dp))}
  }
 }
}
@Composable private fun CalendarView(plans:List<Plan>,card:Color,fg:Color) {
    val current = jalaliParts(LocalDate.now())
    var year by remember { mutableIntStateOf(current.year) }
    var month by remember { mutableIntStateOf(current.month) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    val dayCount = jalaliMonthLength(year,month)
    val firstDay = jalaliToGregorian(year,month,1)
    val offset = (firstDay.dayOfWeek.value + 1) % 7
    val selectedPlan = plans.firstOrNull { it.date == selected.toString() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={ if(month==1){month=12;year--}else month-- }) { Icon(Icons.Default.ChevronLeft,null,tint=fg) }
            Text("${jalaliMonths[month-1]} ${fa(year.toString())}",color=fg,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f),textAlign=TextAlign.Center)
            IconButton(onClick={ if(month==12){month=1;year++}else month++ }) { Icon(Icons.Default.ChevronRight,null,tint=fg) }
        }
        Row { listOf("ش","ی","د","س","چ","پ","ج").forEach { Text(it,Modifier.weight(1f).padding(vertical=8.dp),color=muted,textAlign=TextAlign.Center) } }
        for (week in 0..5) {
            Row {
                for (column in 0..6) {
                    val day = week*7 + column - offset + 1
                    val valid = day in 1..dayCount
                    val date = if(valid) runCatching { jalaliToGregorian(year,month,day) }.getOrNull() else null
                    val active = date == selected
                    val hasPlan = date?.let { d -> plans.any { it.date == d.toString() } } ?: false
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(9.dp))
                        .background(if(active) teal else card).clickable(valid) { if(date!=null) selected=date },
                        contentAlignment=Alignment.Center) {
                        if(valid) Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(fa(day.toString()),color=if(active) ink else fg)
                            if(hasPlan) Box(Modifier.padding(top=2.dp).size(5.dp).background(if(active) ink else teal,CircleShape))
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth().weight(1f),colors=CardDefaults.cardColors(containerColor=card)) {
            Column(Modifier.padding(14.dp)) {
                Text(jalali(selected),color=fg,fontWeight=FontWeight.Bold)
                if(selectedPlan==null) Text("برای این روز برنامه یا گزارشی ثبت نشده است.",color=muted,modifier=Modifier.padding(top=8.dp))
                else {
                    Text("${fa(selectedPlan.minutes.toString())} دقیقه مطالعه · ${fa(selectedPlan.tests.toString())} تست",color=teal,modifier=Modifier.padding(vertical=8.dp))
                    LazyColumn {
                        items(selectedPlan.lessons,key={it.id}) { lesson ->
                            Column(Modifier.fillMaxWidth().padding(vertical=5.dp)) {
                                Text(lesson.name,color=fg,fontWeight=FontWeight.SemiBold)
                                Text("${lesson.topic} · ${lesson.start} تا ${lesson.end}",color=muted,fontSize=12.sp)
                                Text("${fa(lesson.actual.toString())} دقیقه مطالعه · ${fa(lesson.tests.toString())} تست",color=teal,fontSize=12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Settings(c:Context,card:Color,fg:Color,theme:String,setTheme:(String)->Unit){
 val pref=c.getSharedPreferences(PREF,0);var speech by remember{mutableStateOf(pref.getString("speech","fa")?:"fa")};var volume by remember{mutableFloatStateOf(pref.getFloat("volume",1f))}
 Column(Modifier.fillMaxSize().padding(16.dp)){Text("تنظیمات",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold);Card(Modifier.padding(top=12.dp)){Column(Modifier.padding(16.dp)){Text("پوسته",color=fg);Row{listOf("dark" to "تیره","light" to "روشن").forEach{FilterChip(theme==it.first,{setTheme(it.first)},label={Text(it.second)})}};Divider();Text("زبان گفتار",color=fg);Row{FilterChip(speech=="fa",{speech="fa";pref.edit().putString("speech",speech).apply()},label={Text("فارسی")});FilterChip(speech=="en",{speech="en";pref.edit().putString("speech",speech).apply()},label={Text("English")})};Text("بلندی صدا: ${(volume*100).toInt()}٪",color=fg);Slider(volume,{volume=it},onValueChangeFinished={pref.edit().putFloat("volume",volume).apply()});Text("AMZ Pace",color=teal,fontWeight=FontWeight.Bold,fontSize=18.sp);Text("ساخته شده توسط امین",color=muted)}}}
}
