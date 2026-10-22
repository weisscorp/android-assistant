#!/usr/bin/env python3
"""Exercise the production JNI JSON-prefix reader without loading a model."""
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/cpp/brain.cpp").read_text()
function = source[source.index("static std::string ready_answer("):source.index("static void fail(")]
tests = r'''
int main() {
    const std::string answer = "Привет, \"мир\"! {данные} \\ путь\nВторая строка";
    const std::string json = nlohmann::ordered_json{{"answer", answer}, {"topics", {"тема"}}, {"points", {"факт"}}}.dump();
    const auto boundary = json.find(",\"topics\"");
    assert(boundary != std::string::npos);
    for (size_t end = 0; end < boundary; ++end) assert(ready_answer(json.substr(0, end)).empty());
    for (size_t end = boundary; end <= json.size(); ++end) assert(ready_answer(json.substr(0, end)) == answer);
    assert(ready_answer(R"({"answer":"\u043f\u0440\u0438\u0432\u0435\u0442","topics":[])") == "привет");
    assert(ready_answer(R"({"answer":"","topics":[])").empty());
    assert(ready_answer(R"({"other":"ignore","topics":[])").empty());
    assert(ready_answer(R"({"answer":42,"topics":[])").empty());
    assert(ready_answer(R"({"answer":"unfinished\)").empty());
}
'''
with tempfile.TemporaryDirectory() as temporary:
    cpp = Path(temporary) / "answer.cpp"
    cpp.write_text('#include <string>\n#include <cassert>\n#include "nlohmann/json.hpp"\n' + function + tests)
    executable = Path(temporary) / "answer"
    subprocess.run(["c++", "-std=c++17", "-I" + str(ROOT / "vendor/llama.cpp/vendor"), str(cpp), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
print("Native answer-prefix tests passed")
