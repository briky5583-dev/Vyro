package com.vyro.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import java.util.Calendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VyroTheme { VyroApp() } }
    }
}

// Every row carries profileId (family profiles) and synced (offline-first, Firestore sync comes next).
@Entity data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val goal: String = "General fitness",
    val updatedAt: Long = System.currentTimeMillis(), val synced: Boolean = false)

@Entity data class WorkoutSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val profileId: Long,
    val exercise: String, val kg: Float, val reps: Int,
    val loggedAt: Long = System.currentTimeMillis(), val synced: Boolean = false)

@Entity data class MealEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val profileId: Long,
    val name: String, val kcal: Int, val protein: Float, val carbs: Float, val fat: Float,
    val loggedAt: Long = System.currentTimeMillis(), val synced: Boolean = false)

@Entity data class WaterLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val profileId: Long, val ml: Int,
    val loggedAt: Long = System.currentTimeMillis(), val synced: Boolean = false)

@Entity data class WeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val profileId: Long, val kg: Float,
    val loggedAt: Long = System.currentTimeMillis(), val synced: Boolean = false)

@Dao interface VyroDao {
    @Query("SELECT * FROM Profile ORDER BY id") fun profiles(): Flow<List<Profile>>
    @Query("SELECT COUNT(*) FROM Profile") suspend fun profileCount(): Int
    @Query("SELECT MIN(id) FROM Profile") suspend fun firstProfileId(): Long
    @Insert suspend fun addProfile(p: Profile): Long
    @Insert suspend fun addSet(s: WorkoutSet)
    @Insert suspend fun addMeal(m: MealEntry)
    @Insert suspend fun addWater(w: WaterLog)
    @Insert suspend fun addWeight(w: WeightEntry)
    @Query("SELECT * FROM WorkoutSet WHERE profileId = :pid AND loggedAt >= :since ORDER BY id DESC")
    fun sets(pid: Long, since: Long): Flow<List<WorkoutSet>>
    @Query("SELECT * FROM MealEntry WHERE profileId = :pid AND loggedAt >= :since ORDER BY id DESC")
    fun meals(pid: Long, since: Long): Flow<List<MealEntry>>
    @Query("SELECT COALESCE(SUM(ml), 0) FROM WaterLog WHERE profileId = :pid AND loggedAt >= :since")
    fun water(pid: Long, since: Long): Flow<Int>
    @Query("SELECT * FROM WeightEntry WHERE profileId = :pid ORDER BY loggedAt DESC LIMIT 1")
    fun lastWeight(pid: Long): Flow<WeightEntry?>
}

@Database(
    entities = [Profile::class, WorkoutSet::class, MealEntry::class, WaterLog::class, WeightEntry::class],
    version = 1, exportSchema = false)
abstract class VyroDatabase : RoomDatabase() {
    abstract fun dao(): VyroDao
    companion object {
        @Volatile private var instance: VyroDatabase? = null
        fun get(c: Context): VyroDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, VyroDatabase::class.java, "vyro.db")
                .build().also { instance = it }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = VyroDatabase.get(app).dao()
    private val _pid = MutableStateFlow(0L)
    val pid: StateFlow<Long> = _pid

    init {
        viewModelScope.launch {
            _pid.value = if (dao.profileCount() == 0) dao.addProfile(Profile(name = "Me")) else dao.firstProfileId()
        }
    }

    private fun <T> Flow<T>.share(initial: T) =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val profiles = dao.profiles().share(emptyList())
    val sets = _pid.flatMapLatest { dao.sets(it, startOfToday()) }.share(emptyList())
    val meals = _pid.flatMapLatest { dao.meals(it, startOfToday()) }.share(emptyList())
    val water = _pid.flatMapLatest { dao.water(it, startOfToday()) }.share(0)
    val lastWeight = _pid.flatMapLatest { dao.lastWeight(it) }.share(null)

    fun switchProfile(id: Long) { _pid.value = id }
    fun addProfile(name: String) = viewModelScope.launch { dao.addProfile(Profile(name = name)) }
    fun addSet(ex: String, kg: Float, reps: Int) =
        viewModelScope.launch { dao.addSet(WorkoutSet(profileId = _pid.value, exercise = ex, kg = kg, reps = reps)) }
    fun addMeal(name: String, kcal: Int, p: Float, c: Float, f: Float) =
        viewModelScope.launch { dao.addMeal(MealEntry(profileId = _pid.value, name = name, kcal = kcal, protein = p, carbs = c, fat = f)) }
    fun addWater(ml: Int) = viewModelScope.launch { dao.addWater(WaterLog(profileId = _pid.value, ml = ml)) }
    fun addWeight(kg: Float) = viewModelScope.launch { dao.addWeight(WeightEntry(profileId = _pid.value, kg = kg)) }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

val VyroBlue = Color(0xFF1677FF)

private val Dark = darkColorScheme(
    primary = VyroBlue, onPrimary = Color.White,
    background = Color(0xFF080A0E), onBackground = Color(0xFFF2F5FA),
    surface = Color(0xFF12161D), onSurface = Color(0xFFF2F5FA),
    surfaceVariant = Color(0xFF1B2230), onSurfaceVariant = Color(0xFF9AA6B8))

private val Light = lightColorScheme(
    primary = VyroBlue, onPrimary = Color.White,
    background = Color(0xFFF5F7FA), onBackground = Color(0xFF0B1220),
    surface = Color.White, onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFE6ECF5), onSurfaceVariant = Color(0xFF55627A))

@Composable
fun VyroTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)

@Composable
fun VyroApp(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val items = listOf(
        "Home" to Icons.Default.Home, "Workout" to Icons.Default.FitnessCenter,
        "Nutrition" to Icons.Default.Restaurant, "AI Coach" to Icons.Default.AutoAwesome,
        "Profile" to Icons.Default.Person)
    Scaffold(bottomBar = {
        NavigationBar {
            items.forEachIndexed { i, (label, icon) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(icon, label) }, label = { Text(label) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(vm) { tab = it }
                1 -> WorkoutScreen(vm)
                2 -> NutritionScreen(vm)
                3 -> CoachScreen()
                else -> ProfileScreen(vm)
            }
        }
    }
}
