init:
	git submodule update --init
	cd rocket-chip/dependencies && git submodule update --init cde hardfloat diplomacy

compile:
	mill -i CoupledL2.compile
	mill -i CoupledL2.test.compile

verilog:
	mkdir -p build
	mill -i CoupledL2.test.runMain top.TopMain --split-verilog --target systemverilog --full-stacktrace -td build/rtl

clean:
	rm -rf ./build

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.idea.GenIdea/idea

.PHONY: init bsp clean compile idea verilog