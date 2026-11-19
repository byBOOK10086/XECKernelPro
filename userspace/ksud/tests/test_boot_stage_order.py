"""Boot orchestration regressions that run without Android or a Rust build.

Run: python -m unittest discover -s userspace/ksud/tests -p 'test_*.py'
These checks cover ordering and deadline placement, not on-device script execution.
"""

import pathlib
import re
import unittest


SOURCE = pathlib.Path(__file__).resolve().parents[1] / "src" / "init_event.rs"


class BootStageOrderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text(encoding="utf-8")
        cls.boot = source.split("pub fn on_post_fs_data() -> Result<()> {", 1)[1].split(
            "\n///", 1
        )[0]

    def position(self, statement):
        return self.boot.index(statement)

    def test_module_budget_starts_after_preparation(self):
        # Slow first-boot preparation must not expire the module deadline:
        # exec_script_with skips waiting when its deadline has passed.
        module_stage = self.position('metamodule::exec_stage_script("post-fs-data", wait)')
        budgets = list(
            re.finditer(
                r"let wait = ScriptWait::Until\(Instant::now\(\) \+ defs::BOOT_STAGE_TIMEOUT\);",
                self.boot[:module_stage],
            )
        )
        self.assertTrue(budgets, "module scripts need a bounded wait")
        module_budget = budgets[-1].start()
        for setup in (
            "assets::ensure_binaries(true)",
            "handle_updated_modules()",
            "prune_modules()",
            "crate::builtin::install_builtin_modules()",
            "crate::late_load::load_susfs_lkm_for_current_kmi()",
            "crate::module::regenerate_preinit()",
            "restorecon::restorecon()",
            "crate::module::load_sepolicy_rule()",
            "crate::feature::init_features()",
        ):
            with self.subTest(setup=setup):
                self.assertGreater(module_budget, self.position(setup))

    def test_common_scripts_have_a_separate_budget(self):
        common = self.position('crate::module::exec_common_scripts("post-fs-data.d", wait)')
        setup = self.position("assets::ensure_binaries(true)")
        module = self.position('metamodule::exec_stage_script("post-fs-data", wait)')
        declaration = "let wait = ScriptWait::Until(Instant::now() + defs::BOOT_STAGE_TIMEOUT);"
        self.assertIn(declaration, self.boot[:common])
        self.assertIn(declaration, self.boot[setup:module])

    def test_updated_modules_are_ready_before_scripts_and_mount(self):
        ordered = (
            "handle_updated_modules()",
            "crate::kmi_modules::prepare_first_boot()",
            "prune_modules()",
            "crate::module::regenerate_preinit()",
            "crate::module::load_sepolicy_rule()",
            'metamodule::exec_stage_script("post-fs-data", wait)',
            'crate::module::exec_stage_script("post-fs-data", wait)',
            "crate::module::load_system_prop()",
            "mount_modules(module_dir)",
            'run_stage("post-mount", wait)',
        )
        positions = [self.position(statement) for statement in ordered]
        self.assertEqual(positions, sorted(positions))

    def test_offline_manager_install_requires_a_completed_boot(self):
        source = SOURCE.read_text(encoding="utf-8")
        boot_completed = source.split("fn run_boot_completed(", 1)[1]
        manager = "crate::kmi_modules::on_boot_completed()"
        self.assertNotIn(manager, self.boot)
        self.assertLess(
            boot_completed.index("crate::boot_txn::commit_boot_success()"),
            boot_completed.index(manager),
        )


if __name__ == "__main__":
    unittest.main()
