"""防止验证步骤插入后把真实数据库夹具配置绑定到其他步骤。"""
from pathlib import Path
import re
import unittest


def governance_database_step(workflow):
    """按 Actions 的步骤缩进取块，检查配置归属而非仅确认变量出现。"""
    steps = re.split(r"(?m)^      - ", workflow)[1:]
    matches = [step for step in steps if "run: ./mvnw -B -Pgovernance-it verify" in step]
    if len(matches) != 1:
        raise ValueError("真实治理数据库验证步骤必须恰有一个")
    return matches[0]


def has_database_configuration(workflow):
    step = governance_database_step(workflow)
    return all(re.search(rf"(?m)^          {key}: .+$", step) for key in (
        "GOVERNANCE_TEST_DB_URL", "GOVERNANCE_TEST_DB_USER", "GOVERNANCE_TEST_DB_PASSWORD"
    )) and "        env:\n" in step


class CiFixturesTest(unittest.TestCase):
    def test_database_configuration_belongs_to_maven_and_uses_owned_isolated_database(self):
        workflow = (Path(__file__).parents[2] / ".github/workflows/authz-ci.yml").read_text()
        self.assertTrue(has_database_configuration(workflow))
        step = governance_database_step(workflow)
        self.assertIn("jdbc:postgresql://127.0.0.1:5432/auth_gov_p1_test_ci", step)
        self.assertIn("GOVERNANCE_TEST_DB_USER: governance_ci", step)
        self.assertIn("CREATE DATABASE auth_gov_p1_test_ci OWNER governance_ci;", workflow)

    def test_variable_presence_in_a_later_step_does_not_satisfy_the_fixture(self):
        workflow = (Path(__file__).parents[2] / ".github/workflows/authz-ci.yml").read_text()
        broken = workflow.replace(
            "        env:\n          GOVERNANCE_TEST_DB_URL:",
            "      - run: python3 scripts/check-public-api.py\n        env:\n          GOVERNANCE_TEST_DB_URL:",
            1,
        )
        self.assertFalse(has_database_configuration(broken))
