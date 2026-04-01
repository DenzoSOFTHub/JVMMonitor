# Remote Debugging Java Applications with JVMMonitor

## Introduction

JVMMonitor includes a built-in remote debugger that allows you to set breakpoints, inspect variables, step through code, and view decompiled source — all without attaching a traditional debugger like jdb or an IDE remote debug session.

This is particularly useful for:
- **Production debugging** — inspect state without stopping the application globally (only the breakpoint thread pauses)
- **Environments without IDE access** — SSH-only servers, containers, cloud VMs
- **Investigating intermittent bugs** — conditional breakpoints that trigger only when the bug condition is met

> **Important:** The debugger pauses the thread that hits the breakpoint. Other threads continue running normally. However, if the paused thread holds a lock or is processing a request, that specific request will hang until you resume. Use with caution in production.

---

## Enabling the Debugger

The debugger is disabled by default to prevent accidental breakpoints in production.

### GUI

1. Open the **Debugger** tab
2. Click the green **"Enable Debugger"** button in the top toolbar
3. The button turns green and the status shows "Set a breakpoint and wait for it to be hit"

> **What happens when you enable the debugger:** The agent activates JVMTI breakpoint capabilities. This has zero overhead until you actually set a breakpoint. No performance impact from enabling alone.

### CLI

The debugger is GUI-only because it requires interactive variable inspection and source viewing. Use the GUI for debugging.

---

## Setting Breakpoints

### Basic breakpoint

In the Debugger tab toolbar:

1. **Class** field: Enter the fully qualified class name
   - Example: `com.myapp.service.OrderService`
   - You can also use JVM internal format: `com/myapp/service/OrderService`

2. **Line** field: Enter the line number where you want to stop
   - Example: `42`
   - The line must contain executable code (not a comment, blank line, or class declaration)

3. Click **"Set Breakpoint"**

The breakpoint appears in the **Breakpoints** table on the right side.

> **How does it find the line?** The agent uses `jvmti->SetBreakpoint()` which maps to the bytecode instruction at that source line. If the class has no debug info (compiled without `-g`), line-based breakpoints won't work. JVMMonitor decompiles the class to show you the source with line numbers, so you can pick valid lines.

### Conditional breakpoint

Add a condition that must be true for the breakpoint to trigger:

1. **Class**: `com.myapp.service.OrderService`
2. **Line**: `42`
3. **Condition**: `orderId > 1000`

The breakpoint only pauses the thread when the condition evaluates to true.

> **When to use conditional breakpoints:** When investigating an intermittent bug that only occurs for specific inputs. For example, "the order total is negative only for orders with discount > 50%". Set a condition like `discount > 50` and the debugger will pause exactly when the bug condition is met.

> **Performance note:** Conditional breakpoints evaluate the condition every time the line is reached. If the line is in a hot loop (called millions of times), this adds overhead. Use on targeted lines that are not called frequently.

### Removing breakpoints

Select a breakpoint in the Breakpoints table and click **"Remove"**. Or remove all breakpoints by clicking Remove with no selection.

---

## When a Breakpoint is Hit

When a thread reaches a breakpoint (and the condition is satisfied, if set), the following happens:

1. **That thread pauses.** All other threads continue normally.
2. The **Source Code** panel shows the decompiled class source with the breakpoint line highlighted.
3. The **Variables (Watches)** table shows local variables and their values.
4. The **Suspended Threads** list shows the paused thread.

> **What the user sees at this point:** The Debugger tab automatically updates to show the state at the breakpoint. The source code is decompiled on-the-fly from the bytecode retrieved from the agent. If the class was compiled with debug information (`-g` flag), you see meaningful variable names. If not, variables appear as `var0`, `var1`, etc.

### Reading variable values

The **Variables (Watches)** table shows:

| Column | Description |
|---|---|
| **Name** | Variable name (e.g., `orderId`, `customer`, `this`) |
| **Type** | Java type (e.g., `long`, `String`, `com.myapp.model.Order`) |
| **Value** | Current value. Primitives show the value directly. Objects show the toString() result or object ID. |

> **Primitive types** (int, long, double, boolean) show their exact value.
> **String values** show the string content in quotes.
> **Object values** show a summary (class@hashcode or toString if available).
> **null values** show `null`.
> **Arrays** show the length and first few elements.

### Watch expressions

Add custom expressions to evaluate at the breakpoint:

1. In the **Watch Expressions** section, type an expression in the text field
2. Click **"+"** to add it
3. The expression is evaluated at every breakpoint hit

Examples:
- `order.getTotal()` — call a method on a local variable
- `customer.getName()` — access a nested object
- `items.size()` — check collection size
- `this.cache.size()` — check object state

> **Why use watch expressions?** Variables only show local variables and parameters. Watch expressions let you navigate the object graph: call methods, access fields of nested objects, check collection sizes, etc. This is essential for understanding the full state when a bug occurs.

Click **"-"** to remove a watch expression.

---

## Stepping Through Code

Once a thread is paused at a breakpoint, you can step through the code:

### Resume (F8)

**Action:** Continue execution until the next breakpoint (or until the method returns normally).

**When to use:** When you have seen enough at this breakpoint and want the application to continue. Or when you want to reach the next breakpoint.

### Step Over (F6)

**Action:** Execute the current line and pause at the next line in the same method. If the current line calls a method, that method executes completely without pausing inside it.

**When to use:** When you want to move through the code line by line without diving into sub-method calls. For example, if the current line is `int total = calculateTotal(order)`, Step Over executes `calculateTotal` completely and pauses on the next line.

> **Tip:** Use Step Over to quickly scan the flow of a method. Only use Step Into when you suspect the bug is inside a specific method call.

### Step Into (F5)

**Action:** If the current line calls a method, enter that method and pause at its first line. If the current line doesn't call a method, behaves like Step Over.

**When to use:** When you want to trace into a specific method to understand what it does with the current parameters.

> **Tip:** Stepping into JDK methods (String.equals, HashMap.get) is usually not useful. Step Over those and only Step Into your application methods.

---

## Viewing Decompiled Source

### From a breakpoint

When a breakpoint is hit, the **Source Code** panel automatically shows the decompiled source of the class where the breakpoint was set. The breakpoint line is highlighted.

### Browsing any class

Switch to the **Source Viewer** sub-tab to decompile and view any class:

1. The agent retrieves the raw bytecode of the class
2. The integrated DenzoSOFT Java Decompiler converts it to Java source
3. The source is displayed with syntax highlighting

> **Syntax highlighting colors:**
> - **Blue** — Java keywords (public, private, class, if, for, return, etc.)
> - **Green** — String literals ("hello world")
> - **Gray** — Comments (// and /* */)
> - **Red** — Numbers (42, 3.14, 0xFF)
> - **Olive** — Annotations (@Override, @Autowired)

> **Why decompile instead of using source?** In production, you often don't have source code available. The decompiler reconstructs readable Java from bytecode. It supports Java 1.0 through Java 25. The output is not identical to the original source but is functionally equivalent and readable.

---

## Debugging Workflows

### Workflow 1: Investigating a NullPointerException

**Scenario:** The exception log shows `NullPointerException at OrderService.java:87` but you can't reproduce it locally.

1. **Enable the debugger**
2. **Set a breakpoint** at `com.myapp.service.OrderService` line `85` (a couple lines before the NPE)
3. **Wait** for normal traffic to hit the breakpoint
4. **Inspect variables** — check which variable is null
5. **Add watch expressions** to trace back:
   - `order` — is the order null?
   - `order.getCustomer()` — is the customer null?
   - `order.getItems()` — is the items list null?
6. **Step Over** lines 85, 86 to see which line produces null
7. **Resume** to let the application continue

### Workflow 2: Understanding data flow

**Scenario:** A complex calculation produces wrong results for certain inputs.

1. **Set a conditional breakpoint** at the entry of the calculation method:
   - Class: `com.myapp.billing.Calculator`
   - Line: `30` (first line of the calculate method)
   - Condition: `amount > 10000` (only trigger for large amounts)
2. **When hit**, inspect input parameters in the Variables table
3. **Step Over** each line, watching how values change
4. **Add watch expressions** for intermediate results:
   - `subtotal`
   - `discount`
   - `tax`
   - `total`
5. Identify where the calculation diverges from the expected result

### Workflow 3: Verifying a fix in production

**Scenario:** You deployed a fix for a race condition. You want to verify it works.

1. **Set a breakpoint** at the critical section entry
2. **When hit**, check:
   - Is the lock held? (watch: `Thread.holdsLock(this)`)
   - Are the shared variables in the expected state?
3. **Resume** and check the next hit
4. After a few verifications, **remove the breakpoint** and **disable the debugger**

### Workflow 4: Inspecting cached data

**Scenario:** A cache seems to return stale data.

1. **Set a breakpoint** at the cache get method:
   - Class: `com.myapp.cache.LruCache`
   - Line: first line of `get` method
2. **When hit**, inspect:
   - `key` — what key is being looked up
   - `this.cache.size()` — how many entries in the cache
   - `this.cache.get(key)` — what value is cached for this key
3. **Step Over** to see the return value
4. **Resume** and check the next hit with a different key

---

## Safety Considerations

### Production use

| Concern | Mitigation |
|---|---|
| Thread hangs at breakpoint | Set a timeout: resume if not manually resumed within 30s. Or use conditional breakpoints to limit hits. |
| Lock held by paused thread | Other threads waiting for that lock will also block. Minimize time at breakpoint. |
| Breakpoint in hot path | Millions of hits/second will effectively freeze the app. Use conditional breakpoints on hot paths. |
| Debugger left enabled | Disable when done. An enabled debugger with no breakpoints has zero overhead. |

### Best practices

1. **Always use conditional breakpoints in production** — avoid pausing on every invocation
2. **Resume quickly** — inspect variables and resume within seconds
3. **Remove breakpoints when done** — don't leave breakpoints set overnight
4. **Disable the debugger when not needed** — click the toggle button back to disabled
5. **Save a session before debugging** — in case the breakpoint causes issues, you have the pre-debug state saved
6. **Avoid breakpoints in constructors of frequently created objects** — classes like `String`, `ArrayList`, `HashMap`
7. **Don't step into JDK/framework code** — use Step Over for `java.*`, `org.springframework.*`, etc.

### What can go wrong

| Situation | What happens | Recovery |
|---|---|---|
| Breakpoint on a thread holding a database connection | Connection pool may run out while thread is paused | Resume the thread, then fix the breakpoint location |
| Breakpoint in `finalize()` | Finalizer thread pauses, pending finalizations queue up | Remove the breakpoint, resume |
| Breakpoint in a loop with millions of iterations and no condition | Application freezes (every iteration triggers the breakpoint) | Remove the breakpoint from the Breakpoints table, click Resume |
| Class has no debug info | Variables show as `var0`, `var1` | Recompile with `-g` flag or use watch expressions to call methods |

---

## Troubleshooting the Debugger

### "Breakpoint not set" error

- **Cause:** The class is not loaded yet or the line number doesn't exist in the bytecode
- **Fix:** Ensure the class is loaded (it must have been used at least once). Check the decompiled source for valid line numbers.

### Variables show "unavailable"

- **Cause:** The class was compiled without debug info (`-g` flag)
- **Fix:** Recompile with `javac -g` or use watch expressions to call methods on `this`

### Breakpoint never triggers

- **Cause:** The code path is never reached, or the condition is never true
- **Fix:** Check the condition expression. Try without a condition first. Verify the class and line are correct.

### Source code looks different from original

- **Cause:** The decompiler reconstructs source from bytecode, which may differ from the original in formatting, variable names, and syntactic sugar
- **Fix:** This is normal. The logic is equivalent. Focus on the variable values, not the source appearance.
