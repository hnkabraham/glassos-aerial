import importlib.util
from pathlib import Path
import unittest

p = Path(__file__).resolve().parents[2] / "tools/reliability_check.py"
spec = importlib.util.spec_from_file_location("reliability_check", p)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PlayerOwnershipTests(unittest.TestCase):
    def test_fire_os_pid_format(self):
        dump = " Client\n  pid(123), connId(3)\n NuPlayer\n state(5)\n 7 registered handlers:\n"
        self.assertEqual(1, len(module.owned_player_blocks(dump, {"123"})))
        self.assertEqual([], module.owned_player_blocks(dump, {"999"}))

    def test_android_attribution_format(self):
        dump = " Client\n AttributionSource(AttributionSourceState{pid: 456, uid: 10082}), connId(2)\n state(5)\n"
        self.assertEqual(1, len(module.owned_player_blocks(dump, {"456"})))
        self.assertEqual([], module.owned_player_blocks(dump, {"45"}))

    def test_no_player_not_confused_with_decoder_inventory(self):
        self.assertEqual(
            [],
            module.owned_player_blocks(
                "Decoder capabilities: pid(123) state(5)", {"123"}
            ),
        )


if __name__ == "__main__":
    unittest.main()
