# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: buffer/constraints/openeth_frame_cdc.tcl
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
# SPDX-License-Identifier: MIT
# 在综合后的实际设计上source；不创建全局clock_groups/false_path。
namespace eval openeth_cdc {variable contracts {}}

proc openeth_cdc::regs {root stem} {
    set found [get_cells -quiet -hierarchical -filter "NAME =~ $root/${stem}_reg*"]
    if {[llength $found] == 0} {error "ETH01_EMPTY_REGISTER_SET root=$root stem=$stem"}
    foreach c $found {
        if {![string match "FD*" [get_property REF_NAME $c]]} {
            error "ETH01_NOT_FLIPFLOP $c"
        }
    }
    return $found
}

proc openeth_cdc::pins {cells leaf} {
    set pins [get_pins -quiet -of_objects $cells -filter "REF_PIN_NAME == $leaf"]
    if {[llength $pins] != [llength $cells]} {error "ETH01_PIN_COUNT_MISMATCH pin=$leaf"}
    return $pins
}

proc openeth_cdc::period {cells} {
    set clocks [get_clocks -quiet -of_objects [pins $cells C]]
    if {[llength $clocks] != 1} {error "ETH01_EXPECT_ONE_CLOCK cells=$cells clocks=$clocks"}
    set period [get_property PERIOD $clocks]
    if {$period <= 0} {error "ETH01_INVALID_CLOCK_PERIOD $clocks"}
    return $period
}

proc openeth_cdc::driver {pin} {
    set nets [get_nets -quiet -of_objects $pin]
    set drivers [get_pins -quiet -leaf -of_objects $nets -filter {DIRECTION == OUT}]
    if {[llength $drivers] != 1} {error "ETH01_EXPECT_ONE_DRIVER pin=$pin drivers=$drivers"}
    return [lindex $drivers 0]
}

proc openeth_cdc::check_release {root stem clock_stem} {
    set rel [regs $root $stem]
    if {[llength $rel] != 2} {error "ETH01_RESET_RELEASE_WIDTH root=$root stem=$stem"}
    set first {}; set second {}
    foreach c $rel {
        if {![string equal -nocase [get_property ASYNC_REG $c] TRUE] &&
            [get_property ASYNC_REG $c] ne "1"} {error "ETH01_RESET_RELEASE_ATTRIBUTE $c"}
        if {[get_property REF_NAME $c] ne "FDCE"} {error "ETH01_RESET_NOT_ASYNC_CLEAR $c"}
        if {[regexp {_reg\[0\]$} $c]} {set first $c}
        if {[regexp {_reg\[1\]$} $c]} {set second $c}
    }
    if {$first eq "" || $second eq ""} {error "ETH01_RESET_RELEASE_INDEX root=$root stem=$stem"}
    set a [driver [pins $first D]]
    set b [driver [pins $second D]]
    if {[get_property REF_NAME [get_cells -of_objects $a]] ne "VCC" ||
        $b ne [pins $first Q]} {error "ETH01_RESET_RELEASE_CHAIN root=$root stem=$stem"}
    set wanted [get_clocks -quiet -of_objects [pins [regs $root $clock_stem] C]]
    set actual [get_clocks -quiet -of_objects [pins $rel C]]
    if {$actual ne $wanted || [llength $wanted] != 1} {error "ETH01_RESET_RELEASE_CLOCK root=$root stem=$stem"}
    puts "ETH01_RESET_RELEASE_PASS root=$root stem=$stem clock=$actual"
    return $rel
}

proc openeth_cdc::check_reset {root} {
    set wr [check_release $root wr_release wgray]
    set rd [check_release $root rd_release rgray]
    foreach {rel stems} [list $wr {wbin wgray rgray_w1 rgray_w2} $rd {rbin rgray wgray_r1 wgray_r2 rkeep}] {
        foreach stem $stems {check_local_clear [regs $root $stem] $rel}
    }
    puts "ETH01_LOCAL_RESET_PASS root=$root"
}

# 不能只验证ASYNC_REG：逐个CLR的唯一时序起点必须是同域二级Q。
proc openeth_cdc::release_second {rel} {
    foreach c $rel {if {[regexp {_reg\[1\]$} $c]} {return $c}}
    error "ETH01_RELEASE_SECOND_MISSING $rel"
}

proc openeth_cdc::check_local_clear {state rel} {
    set second [release_second $rel]
    # Vivado all_fanin的顺序元件起点以C表示（Q是组合驱动端，但不是合法时序起点）。
    set start [pins $second C]
    set wanted [get_clocks -of_objects [pins $second C]]
    foreach c $state {
        set clr [get_pins -quiet -of_objects $c -filter {REF_PIN_NAME == CLR}]
        if {[llength $clr] != 1} {error "ETH01_STATE_WITHOUT_ASYNC_CLEAR $c"}
        set from [all_fanin -flat -startpoints_only -to $clr]
        if {[lsort $from] ne [lsort $start]} {error "ETH01_LOCAL_CLEAR_SOURCE cell=$c expected=$start actual=$from"}
        if {[get_clocks -of_objects [pins $c C]] ne $wanted} {error "ETH01_LOCAL_CLEAR_CLOCK $c"}
        # 本地二级到业务CLR必须保留正常恢复/移除路径，不能用例外遮掉。
        set paths [get_timing_paths -quiet -from [pins $second C] -to $clr -max_paths 1]
        if {[llength $paths] != 1 || ![string is double -strict [get_property REQUIREMENT $paths]]} {
            error "ETH01_LOCAL_CLEAR_TIMING_MASKED $c"
        }
    }
}

proc openeth_cdc::chain {root source first second} {
    set src [regs $root $source]
    set a [regs $root $first]
    set b [regs $root $second]
    if {[llength $src] != [llength $a] || [llength $a] != [llength $b]} {
        error "ETH01_GRAY_WIDTH_MISMATCH root=$root source=$source"
    }
    foreach c [concat $a $b] {
        if {![string equal -nocase [get_property ASYNC_REG $c] TRUE] &&
            [get_property ASYNC_REG $c] ne "1"} {error "ETH01_MISSING_ASYNC_REG $c"}
    }
    # 检查同步链直接相连，而不是仅靠名字判断两级。
    foreach {stage expected} [list $a $src $b $a] {
        foreach d [pins $stage D] {
            set n [get_nets -quiet -of_objects $d]
            set drivers [get_pins -quiet -leaf -of_objects $n -filter {DIRECTION == OUT}]
            set owners [get_cells -quiet -of_objects $drivers]
            if {[llength $owners] != 1 || [lsearch -exact $expected [lindex $owners 0]] < 0} {
                error "ETH01_NON_DIRECT_SYNC_CHAIN $d drivers=$drivers"
            }
        }
    }
    set budget [expr {min([period $src], [period $a])}]
    # C是合法时序起点；Q会切断时序弧并破坏clock_groups优先级检查。
    set q [pins $src C]
    set d [pins $a D]
    set_max_delay -datapath_only $budget -from $q -to $d
    set_bus_skew $budget -from $q -to $d
    variable contracts
    lappend contracts [list $root $source $q $d $budget]
    puts "ETH01_GRAY_CONSTRAINED root=$root source=$source bits=[llength $q] budget_ns=$budget"
}

proc openeth_cdc::apply {roots} {
    variable contracts
    set contracts {}
    foreach root $roots {
        set exact [get_cells -quiet $root]
        if {[llength $exact] != 1 || ![string match openeth_frame_fifo* [get_property REF_NAME $exact]]} {
            error "ETH01_FIFO_ROOT_MISMATCH $root"
        }
        chain $root wgray wgray_r1 wgray_r2
        chain $root rgray rgray_w1 rgray_w2
        check_reset $root
        # 元数据先于提交指针发布，并在槽回收前稳定；仍限制其实际传播。
        set metadata [concat [regs $root word_lengths] [regs $root tail_keeps] [regs $root errors]]
        set sinks [concat [regs $root rlength] [regs $root rkeep] [regs $root read_error]]
        set budget [expr {min([period $metadata], [period $sinks])}]
        set q [pins $metadata C]
        set d [pins $sinks D]
        set_max_delay -datapath_only $budget -from $q -to $d
        set_bus_skew $budget -from $q -to $d
        lappend contracts [list $root stable_metadata $q $d $budget]
        puts "ETH01_METADATA_CONSTRAINED root=$root source_bits=[llength $q] sink_bits=[llength $d] budget_ns=$budget"
    }
    validate
}

proc openeth_cdc::validate {} {
    variable contracts
    if {[llength $contracts] == 0} {error "ETH01_NO_CONSTRAINT_CONTRACT"}
    foreach contract $contracts {
        lassign $contract root source q d budget
        # 逐目标位检查可计时路径；更高优先级false_path/clock_groups会令其消失。
        foreach target $d {
            set paths [get_timing_paths -quiet -from $q -to $target -max_paths 1]
            if {[llength $paths] != 1} {
                error "ETH01_PATH_MASKED_OR_MISSING root=$root source=$source target=$target"
            }
            set req [get_property REQUIREMENT $paths]
            if {![string is double -strict $req]} {
                error "ETH01_PATH_MASKED_OR_MISSING root=$root source=$source target=$target requirement=$req"
            }
            if {abs($req-$budget) > 0.001} {
                error "ETH01_WRONG_REQUIREMENT target=$target expected=$budget actual=$req"
            }
        }
        # 每个槽的源位也必须有路径，防止某一槽被局部false_path而其他槽掩盖。
        foreach start $q {
            set paths [get_timing_paths -quiet -from $start -to $d -max_paths 1]
            set req [get_property REQUIREMENT $paths]
            if {[llength $paths] != 1 || ![string is double -strict $req]} {
                error "ETH01_PATH_MASKED_OR_MISSING root=$root source=$source start=$start"
            }
            if {abs($req-$budget) > 0.001} {
                error "ETH01_WRONG_REQUIREMENT start=$start expected=$budget actual=$req"
            }
        }
    }
    puts "ETH01_TIMING_COVERAGE_PASS groups=[llength $contracts]"
}

proc openeth_cdc::check_memory {roots} {
    foreach root $roots {
        set memories [get_cells -quiet -hierarchical -filter "NAME =~ $root/memory_reg_bram* && REF_NAME == RAMB36E2"]
        if {[llength $memories] != 2} {error "ETH01_EXPECT_TWO_RAMB36_PER_FIFO root=$root actual=[llength $memories]"}
        set write_clocks [get_clocks -quiet -of_objects [pins [regs $root wgray] C]]
        set read_clocks [get_clocks -quiet -of_objects [pins [regs $root rgray] C]]
        foreach ram $memories {
            set ca [get_clocks -quiet -of_objects [get_pins $ram/CLKARDCLK]]
            set cb [get_clocks -quiet -of_objects [get_pins $ram/CLKBWRCLK]]
            if {$ca ne $write_clocks || $cb ne $read_clocks} {
                error "ETH01_RAM_CLOCK_MISMATCH ram=$ram write=$ca read=$cb"
            }
            puts "ETH01_RAM_INFERENCE_PASS ram=$ram write=$ca read=$cb DOB_REG=[get_property DOB_REG $ram]"
        }
    }
}
