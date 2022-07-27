bundle agent ctest_18205
{
&if(!DENYBADCLOCKS)&
# this should be written
&endif&

&if(DENYBADCLOCKS)&
# not that
&endif&
}
