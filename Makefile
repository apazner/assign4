JFLAGS = -g
JC = javac

SRC := $(wildcard *.java)
CLS := $(SRC:.java=.class)

all: $(CLS)

%.class: %.java
	$(JC) $(JFLAGS) $<

clean:
	rm -f *.class