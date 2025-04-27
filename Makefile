init:
	git submodule update --init
	cd rocket-chip/dependencies && git submodule update --init cde hardfloat diplomacy

compile:
	mill -i CoupledL2.compile
	mill -i CoupledL2.test.compile

RTL_DIR = build/rtl
MKDIR_CMD = mkdir -p $(RTL_DIR)
ifeq ($(OS),Windows_NT)
MKDIR_CMD = powershell 'New-Item -Force -Path $(RTL_DIR) -ItemType Directory | Out-Null'
endif

verilog:
	@$(MKDIR_CMD)
	mill -i CoupledL2.test.runMain top.TopMain --split-verilog --target systemverilog --full-stacktrace -td build/rtl

clean:
	rm -rf ./build

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.idea.GenIdea/idea

.PHONY: init bsp clean compile idea verilog