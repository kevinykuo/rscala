assert <- function(xx,label) {
  cat(label)
  # if ( is.raw(xx) && ( label == 5 ) ) browser()
  cat("a")
  if ( ! identical(( s %~% 'R.get("xx")._1' ), xx) ) stop("Not identical (test 1)")
  cat("b")
  if ( ! identical(( s %~% 'R.xx._1' ), xx) ) stop("Not identical (test 2)")
  cat("c")
  s$xx <- xx
  s %@% 'R.a = xx'
  if ( ! identical(a, xx) ) {
    stop("Not identical (test 3)")
  }
  cat("d")
  if ( ! identical(s$.R$get("xx")$"_1"(), xx) ) stop("Not identical (test 4)")
  cat("e")
  m <- function() s %!% 'R.get("xx")._1'
  if ( ! identical(m(), xx) ) stop("Not identical (test 5)")
  cat(" ")
}

row.major <- FALSE
source("common.R",print.eval=TRUE)

y <- c(0,1,2,3,4,5,6,8)
for ( x in list(as.integer(y),as.double(y),as.logical(y),as.character(y),as.raw(y)) ) {
  cat("Class is",class(x),"\n")
  assert(x[1],1)
  assert(x[2],2)
  assert(x,3)
  assert(matrix(x,nrow=1),4)
  assert(matrix(x,nrow=2),5)
  assert(matrix(x,nrow=4),6)
  cat("\n")
}

row.major <- TRUE
source("common.R",print.eval=TRUE)

y <- c(0,1,2,3,4,5,6,8)
for ( x in list(as.integer(y),as.double(y),as.logical(y),as.character(y),as.raw(y)) ) {
  cat("Class is",class(x),"\n")
  assert(x[1],1)
  assert(x[2],2)
  assert(x,3)
  assert(matrix(x,nrow=1),4)
  assert(matrix(x,nrow=2),5)
  assert(matrix(x,nrow=4),6)
  cat("\n")
}


#### Callbacks with named arguments

mySort <- function(x, ascending=TRUE) s %!% '
  R.invokeD1( EphemeralReference("sort"), x, "decreasing" -> ! ascending )
'

set.seed(13242)
mySort(runif(12),FALSE)

#### Get: Unsupported type

g <- list(3)
tryCatch(s %@% 'R.g', error=function(e) e)
s %~% "3-2"

#### Get Undefined items

tryCatch(s %@% 'R.ggg', error=function(e) e)
s %~% "3+0"

#### Set: Unsupported non-array type

tryCatch(s %@% 'R.x = List(1,2,3)', error=function(e) e)
s %~% "3+2"

#### Set: Unsupported array type

tryCatch(s %@% 'R.x = Array(new scala.util.Random())', error=function(e) e)
s %~% "3+4"

#### Set: Unsupported ragged array type

tryCatch(s %@% 'R.x = Array(Array(1.0),Array(2.0,3.0))', error=function(e) e)
s %~% "3+6"

#### Setting with indices using single brackets

x <- matrix(1:4,nrow=2)
y <- x
s %@% 'R.set("x",Array(Array(10.0)),"1,1",false)'
y[1,1] <- 10
identical(x,y)

tryCatch(s %@% 'R.set("x",Array(Array(10.0)),"1,1asdf",false)', error=function(e) e)
s %~% '"Okay 1"'

#### Setting with indices using double brackets

x <- as.list(letters)
y <- x
s %@% 'R.set("x","Z","26",true)'
y[[26]] <- "Z"
identical(x,y)

s %@% 'R.set("x","Z","\'bob\'",true)'
y[["bob"]] <- "Z"
identical(x,y)
s %~% '"Okay 2"'

####

counter <- 0
for ( i in 1:10 ) {
  s %~% 'R.eval("counter <- counter + 1")'
}
if ( counter != 10 ) stop("Counter is off.")
for ( i in 1:10 ) {
  s$.R$eval("counter <<- counter - 1")
}
if ( counter != 0 ) stop("Counter is off.")


# Should be a compile-time error because 'ewf' is not defined.
tryCatch(s %~% '
  3+4+ewf
  R.eval("""
    cat("I love Lisa!\n")
    a <- "3+9"
  """)
',error=function(e) e)
s %~% '3+2'


# Should be an R evaluation error because 'asfd' is not defined and out of place.
tryCatch(s %~% '
  3+4
  R.eval("""
    cat("I love Lisa!\n")
    a <- "3+9" asfd
  """)
',error=function(e) e)
s %~% '3+6'

###

myMean <- function(data,offset) {
  cat("Here is am.\n")
  mean(data+offset)
}

callRFunction <- function(func, x, y) s %!% '
  R.invokeD1(func,x.map(2*_).map(_.getClass),y)
'

tryCatch(callRFunction(myMean,1:4,II(5)), error=function(e) e)
s %~% "3+4"

callRFunction <- function(func, x, y) s %!% '
  R.invokeD1(func,x.map(2*_),y)
'

callRFunction(myMean,y=0,x=1:100)

callRFunction0D2 <- function(func) s %!% 'R.invokeD2(func)'
callRFunction0D2(gc)

tryCatch(callRFunction(1:100),error = function(e) {})

# Should be an R evaluation error because 'asfd' is not a package.
tryCatch(s %@% 'R.eval("library(asdf)")',error=function(e) e)
s %~% 'R.evalD0("3+4")'

# Note that callbacks can be infinitely deep.
s %~% "3+4"
s %~% 'R.evalD0("""s %~% "2+3"""")'
s %~% "3+4"


