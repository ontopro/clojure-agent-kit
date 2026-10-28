(ns harness.setup.template-fixture
  "The three files the KIT rewrites in a generated application, from the pinned template at
  `kit-v1.3` (f12268d), copied by hand: `.mise.toml` whole, the Dockerfile and `.gitignore` cut to
  the lines the KIT rewrites and their neighbours. The tests of the set's rewrites, and the stand-in
  template of the upgrade tests, read the template's shape and not one written for the test.
  A template release that changes them is noticed by `bb health`, which generates from the pin.")

(def mise
  (str "# A KNOWN-GOOD SET, not a set of requirements: these versions were run together on\n"
       "# 2026-09-21 (macOS, arm64) against a project generated from this template - format\n"
       "# check, lint, tests, and the dev system booted and served. Newer versions are expected\n"
       "# to work and are not a fault. The one real constraint is the JDK: stay on 21 (XTDB,\n"
       "# an optional store, documents a minimum of 21 and its early v2 releases failed at\n"
       "# class-load on newer JDKs). Babashka must be 1.12.212 or newer for bbin.\n"
       "#\n"
       "# mise is optional. With it: `mise trust && mise install` - which DOWNLOADS a JDK and\n"
       "# five tools, through third-party asdf plugins for the last two. If these tools are\n"
       "# already on your machine from elsewhere, you do not need to run it.\n"
       "[tools]\n"
       "java = \"temurin-21\"\n"
       "clojure = \"1.12.6.1673\"\n"
       "babashka = \"1.13.223\"\n"
       "clj-kondo = \"2026.08.04\"\n"
       "cljfmt = \"0.16.5\"\n"
       "tailwindcss = \"4.3.3\"\n"
       "\n"
       "[alias]\n"
       "cljfmt = \"asdf:https://github.com/b-social/asdf-cljfmt\"\n"
       "tailwindcss = \"asdf:https://github.com/virtualstaticvoid/asdf-tailwindcss\"\n"))

(def dockerfile
  (str "FROM clojure:temurin-21-tools-deps-1.12.1.1550-alpine AS build\n"
       "\n"
       "WORKDIR /app\n"
       "\n"
       "ARG BB_VERSION=1.12.206\n"
       "ARG TAILWIND_VERSION=v4.1.11\n"
       "\n"
       "# Install tailwindcss\n"
       "RUN wget -qO /usr/local/bin/tailwindcss \"https://github.com/tailwindlabs/tailwindcss/releases/download/${TAILWIND_VERSION}/tailwindcss-linux-x64-musl\" \\\n"
       "    && chmod +x /usr/local/bin/tailwindcss\n"
       "\n"
       "# Build uberjar\n"
       "COPY . /app\n"
       "RUN bb build\n"
       "\n"
       "\n"
       "FROM eclipse-temurin:21.0.2_13-jre-alpine\n"
       "\n"
       "WORKDIR /app\n"
       "COPY --from=build /app/target/standalone.jar /app/standalone.jar\n"
       "\n"
       "EXPOSE 80\n"
       "CMD [\"java\", \"-Xmx256m\", \"-jar\", \"standalone.jar\"]\n"))

(def gitignore
  (str ".cpcache\n"
       ".nrepl-port\n"
       "/target\n"
       ".env\n"
       ".env.local\n"
       "resources/public/css/output.*\n"))

(def files
  "The three, by path - to merge into a stand-in template's tree."
  {".mise.toml" mise "Dockerfile" dockerfile ".gitignore" gitignore})
