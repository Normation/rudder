def upperstring(input):
    return input.upper()
def the_answer(value):
    return value == "42"

FILTERS = {'upperstring': upperstring}
TESTS = {'the_answer': the_answer}