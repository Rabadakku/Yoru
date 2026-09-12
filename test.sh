#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build.sh
find src/test/java -name '*.java' > build/tests.txt
javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes @build/tests.txt
java -ea -cp build/classes dev.yoru.CoreTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.UiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ExpansionTest

java -ea -cp build/classes dev.yoru.HabitsTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.InputTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.LogoTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.ContrastTest
java -ea -cp build/classes dev.yoru.SchemaTest
java -ea -cp build/classes dev.yoru.DistributionTest
java -ea -cp build/classes dev.yoru.PrivacyTest
java -ea -cp build/classes dev.yoru.LegacyVaultTest
java -ea -cp build/classes dev.yoru.persistence.VaultStoreTest
java -ea -cp build/classes dev.yoru.VaultSwitchTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.VaultUiTest
java -ea -cp build/classes dev.yoru.assets.ArtworkTest
java -ea -cp build/classes dev.yoru.assets.MusicTest
java -ea -cp build/classes dev.yoru.game.Gen3FormatTest
java -ea -cp build/classes dev.yoru.game.Gen3SaveTest
java -ea -cp build/classes dev.yoru.game.ExperienceTest
java -ea -cp build/classes dev.yoru.game.EmulationLoopTest
java -ea -cp build/classes dev.yoru.game.SessionHandleTest
java -ea -cp build/classes dev.yoru.game.LearnsetTest
java -ea -cp build/classes dev.yoru.game.StudyGiftTest
java -ea -cp build/classes dev.yoru.game.StatsTest
java -ea -cp build/classes dev.yoru.game.EncounterTest
java -ea -cp build/classes dev.yoru.game.GameDeliveryTest
java -ea -cp build/classes dev.yoru.game.StorageEditTest
java -ea -cp build/classes dev.yoru.SaveEditTest
java -ea -cp build/classes dev.yoru.RewardTest
java -ea -cp build/classes dev.yoru.ReliabilityTest
java -ea -cp build/classes dev.yoru.ActivitiesTest
java -ea -cp build/classes dev.yoru.PortableVaultTest
java -ea -cp build/classes dev.yoru.RecurringTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.RouteTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TaskBoardTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.ScheduleUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TaskCalendarTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.FocusMixTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.StorageTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.PackagedArtworkTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.ActivityUiTest

java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.WaifuUiTest

java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.BuddySceneTest

java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.BuddyCardTest

java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TaskPasteTest

java -ea -cp build/classes dev.yoru.importer.NotionTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.NotionImportTest

java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.RouteCameoTest
