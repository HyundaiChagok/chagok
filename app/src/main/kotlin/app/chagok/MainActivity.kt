package app.chagok

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.chagok.rental.RentalController
import app.chagok.ui.ChagokScreen
import app.chagok.ui.ChagokTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as ChagokApplication).graph
        val controller: RentalController by viewModels { graph.controllerFactory }
        setContent {
            ChagokTheme {
                ChagokScreen(
                    controller = controller,
                    demo = graph.demo,
                    vehicleId = graph.settings.vehicleId,
                    sourceLabel = graph.sourceLabel,
                    fakeOnly = graph.sourceLabel == "가짜 차량",
                    speaking = graph.speaker.speaking,
                )
            }
        }
    }
}
