import client as RWXENV

env = RWXENV.make("CASE1", render_mode="human")

observation, info = env.reset()

terminated = False
truncated = False
i = 0

while not terminated and not truncated:
    action = env.action_space.sample()

    observation, reward, terminated, truncated, info = env.step(action)

    print(f">>> {i} >>>")
    i += 1
    print("observation:", observation)
    print("reward:", reward)
    print("terminated:", terminated)

env.close()